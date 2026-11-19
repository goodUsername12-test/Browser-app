'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', 'src', 'main', 'assets', 'bloxd');
const protocol = JSON.parse(fs.readFileSync(path.join(ROOT, 'bloxd_protocol_map.json'), 'utf8'));
const worldMeta = JSON.parse(fs.readFileSync(path.join(ROOT, 'bloxd_world_meta.json'), 'utf8'));
const rawSeed = JSON.parse(fs.readFileSync(path.join(ROOT, 'localforage_seed.json'), 'utf8'));
const engineMod = require(path.join(ROOT, 'local_bloxd_engine.js'));

const flushAsync = () => new Promise((r) => setTimeout(r, 15));

class TestRoomClient {
  constructor(engine, url) {
    this.engine = engine;
    this.ws = new engine.LocalWebSocket(url);
    this.frames = [];
    this.messagesByOpcode = new Map();
    this.entitySettings = {};
    this.clientOptions = {};
    this.serializer = new engine.rt.coly.oFF();
    this.hasJoined = false;
    this.closed = false;

    this.ws.onmessage = (ev) => {
      const dec = engine.decodeServerFrame(ev.data);
      this.frames.push(dec);
      if (dec.kind === 'JOIN_ROOM') {
        this.sessionId = dec.sessionId;
        this.serializerId = dec.serializerId;
        if (dec.serializerId === 'schema' && dec.handshake.length > 0) {
          this.serializer.handshake(dec.handshake, { offset: 0 });
        }
        this.hasJoined = true;
        this.ws.send(Uint8Array.from([engine.PROTOCOL.JOIN_ROOM]));
      } else if (dec.kind === 'ROOM_STATE') {
        this.serializer.setState(dec.bytes, { offset: 0 });
      } else if (dec.kind === 'ROOM_STATE_PATCH') {
        this.serializer.patch(dec.bytes, { offset: 0 });
      } else if (dec.kind === 'ROOM_DATA' || dec.kind === 'ROOM_DATA_BYTES') {
        if (!this.messagesByOpcode.has(dec.opcode)) this.messagesByOpcode.set(dec.opcode, []);
        this.messagesByOpcode.get(dec.opcode).push(dec.data);
        if (dec.opcode === 106 && dec.data && dec.data.eId) {
          this.entitySettings[dec.data.eId] = {
            ...(this.entitySettings[dec.data.eId] || {}),
            ...(dec.data.settings || {}),
          };
        }
        if (dec.opcode === 1 && dec.data) {
          Object.assign(this.clientOptions, dec.data);
        }
      }
    };
    this.ws.onclose = () => { this.closed = true; };
  }

  get state() {
    return this.serializer.getState();
  }

  lastMsg(opcode) {
    const arr = this.messagesByOpcode.get(opcode) || [];
    return arr.length > 0 ? arr[arr.length - 1] : undefined;
  }

  sendC2S(opcode, payload, useCipher = true) {
    const frame = this.engine.encodeClientMessage(opcode, payload, useCipher);
    this.ws.send(frame);
  }
}

async function run() {
  const results = [];
  async function check(name, fn) {
    await fn();
    results.push(name);
    console.log(`PASS ${name}`);
  }

  await check('1. foundation: protocol map, chunk layout, RLE codec, world metadata, atlas seed', async () => {
    assert.equal(protocol.opcodes.Tb, 64);
    assert.equal(protocol.opcodes.oc, 56);
    assert.equal(protocol.opcodes.db, 24);
    assert.equal(protocol.opcodes.Ah, 103);
    assert.equal(engineMod.chunkIndex(16, 28, 16), 17296);

    const vals = new Uint16Array(32768);
    vals[17296] = 4;
    vals[17297] = 4;
    vals[17298] = 3;
    const enc = engineMod.rleEncode(vals);
    const dec = engineMod.rleDecode(enc, 32768);
    assert.equal(dec[17296], 4);
    assert.equal(dec[17298], 3);

    const seeded = engineMod.prepareAtlasSeed(rawSeed);
    assert.equal(seeded.atlasCache.identity, 'v2|tinyBits|3|856');
    assert.equal(seeded.atlasCache.bundle.blockAtlasPixels.length, 5);
    assert.ok(seeded.atlasCache.bundle.blockAtlasPixels.every((p) => p === null || p instanceof Uint8ClampedArray));
    assert.ok(seeded.atlasCache.bundle.blockAtlasPixels[0].length > 0);
  });

  const sharedBacking = new Map();
  const engine = await engineMod.createLocalBloxdEngine({
    protocolMap: protocol,
    worldMeta,
    backingMap: sharedBacking,
  });

  await check('2. protocol: social room & game room Colyseus JOIN_ROOM, Schema handshake, state, _isAlive, spawn', async () => {
    const social = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/social_room_01?sessionId=soc1');
    await flushAsync();
    assert.equal(social.hasJoined, true);
    assert.equal(social.serializerId, 'none');
    social.ws.close();

    const c1 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__test_world?sessionId=s1');
    await flushAsync();
    assert.equal(c1.hasJoined, true);
    assert.equal(c1.serializerId, 'schema');
    assert.ok(c1.state, 'RoomState decoded');
    const p1 = c1.state.entities.get('1');
    assert.ok(p1, 'Local entity 1 exists');
    assert.equal(p1.x, 16.5);
    assert.equal(p1.y, -2);
    assert.equal(p1.z, 16.5);
    assert.equal(c1.entitySettings['1']._isAlive, true);
    assert.deepEqual(c1.lastMsg(64), { aE: '1', qE: [] });
    assert.equal(c1.lastMsg(56).pE, 'fable_1');
    assert.equal(c1.lastMsg(107).Y5, 1.0);
    assert.equal(c1.clientOptions.useInventory, true);

    // Chunk request & deterministic RLE round-trip
    c1.sendC2S(103, { iH: '0|-1|0', JsF: null, ff: null, Tf: 'req1', Af: 1 });
    c1.sendC2S(103, { iH: '0|-1|0', JsF: null, ff: null, Tf: 'req2', Af: 1 });
    await flushAsync();
    const chunkMsgs = c1.messagesByOpcode.get(24) || [];
    assert.equal(chunkMsgs.length, 2);
    assert.deepEqual(chunkMsgs[0].q_, chunkMsgs[1].q_, 'Deterministic chunk generation');
    const voxels = engineMod.rleDecode(chunkMsgs[0].q_, 32768);
    // Chunk (0, -1, 0) covers y = -32..-1. At (16, -4, 16) -> ly = 28 -> Grass Block (4); ly = 0 (y=-32) -> Bedrock (147); ly = 30 (y=-2) -> Air (0)
    assert.equal(voxels[engineMod.chunkIndex(16, 28, 16)], 4);
    assert.equal(voxels[engineMod.chunkIndex(16, 0, 16)], 147);
    assert.equal(voxels[engineMod.chunkIndex(16, 30, 16)], 0);

    c1.ws.close();
    await flushAsync();
    assert.equal(c1.closed, true);
  });

  await check('3. survival: movement, collision, physics, block break/place, drops/pickup, creative vs survival', async () => {
    const c1 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__surv_world?sessionId=s2');
    await flushAsync();
    const world = await engine.getOrCreateWorld('surv_world');
    const sess = Array.from(world.sessions.values())[0];

    // Valid open-air move
    c1.sendC2S(111, {
      Av: 17.5, Tv: -2, Qv: 16.5, RP: 0.2, loF: 1.1, FQ: 0,
      yR: false, kR: true, xR: 0, jR: true,
      vR: [17.5, -2, 16.5], bR: [0, 0, 0], gR: 1, HQ: [0, 0, 0], EQ: false, pY: 'normal',
    });
    await flushAsync();
    assert.equal(c1.state.entities.get('1').x, 17.5);
    assert.equal(c1.state.entities.get('1').crouching, true);

    // Solid block collision rejection (y = -4 is solid Grass Block)
    c1.sendC2S(111, {
      Av: 17.5, Tv: -4, Qv: 16.5, RP: 0, loF: 0, FQ: 0,
      yR: false, kR: false, xR: 0, jR: false,
      vR: [17.5, -4, 16.5], bR: [0, 0, 0], gR: 2, HQ: [0, 0, 0], EQ: false, pY: 'normal',
    });
    await flushAsync();
    assert.equal(c1.state.entities.get('1').y, -2, 'Solid ground collision blocked underground move');

    // Physics gravity & jump
    sess.y = -1.5;
    sess.onGround = false;
    for (let i = 0; i < 20; i++) engine.stepSessionPhysics(world, sess, 0.05, {});
    assert.equal(sess.onGround, true);
    assert.ok(Math.abs(sess.y - (-3.0)) < 0.01, `Landed on top of y=-4 surface at y=${sess.y}`);
    engine.stepSessionPhysics(world, sess, 0.05, { jump: true });
    assert.ok(sess.y > -3.0, 'Jump increased Y');

    // Break grass block at (18, -4, 16) -> spawns Grass Block drop
    c1.sendC2S(43, { bV: [18, -4, 16], jG: 0, zV: 'Wood Pickaxe', mV: false, tV: false, qV: false });
    await flushAsync();
    assert.equal(engine.getWorldBlock(world, 18, -4, 16), 0);
    assert.deepEqual(c1.lastMsg(36), { o_: [18, -4, 16], Q_: 0 });
    assert.equal(c1.state.items.size, 1, 'Breaking block spawned dropped item');

    // Move within pickup radius to collect drop
    c1.sendC2S(111, {
      Av: 18.2, Tv: -3, Qv: 16.5, RP: 0, loF: 0, FQ: 0,
      yR: false, kR: false, xR: 0, jR: false,
      vR: [18.2, -3, 16.5], bR: [0, 0, 0], gR: 3, HQ: [0, 0, 0], EQ: false, pY: 'normal',
    });
    await flushAsync();
    assert.equal(c1.state.items.size, 0, 'Drop picked up into inventory');

    // Select slot 1 (Dirt, 32) and place Dirt at (18, -4, 16) -> consumes 1 Dirt in survival
    c1.sendC2S(93, 1);
    c1.sendC2S(43, { bV: [18, -4, 16], jG: 2, zV: 'Dirt', mV: true, tV: false, qV: false });
    await flushAsync();
    assert.equal(engine.getWorldBlock(world, 18, -4, 16), 2);
    const invAfterSurvPlace = JSON.parse(c1.lastMsg(91).L_.N_);
    assert.equal(invAfterSurvPlace[1].amount, 31, 'Survival block placement consumed 1 Dirt');

    // Switch to creative via /gamemode creative -> placement does NOT consume items
    c1.sendC2S(38, { Nk: '/gamemode creative', Zm: null });
    await flushAsync();
    assert.equal(c1.clientOptions.creative, true);
    c1.sendC2S(43, { bV: [19, -3, 16], jG: 2, zV: 'Dirt', mV: true, tV: false, qV: false });
    await flushAsync();
    assert.equal(engine.getWorldBlock(world, 19, -3, 16), 2);
    assert.equal(sess.inventory[1].amount, 31, 'Creative placement did not consume Dirt');

    // Switch back to survival
    c1.sendC2S(38, { Nk: '/gamemode survival', Zm: null });
    await flushAsync();
    assert.equal(c1.clientOptions.creative, false);
    c1.ws.close();
    await flushAsync();
  });

  await check('4. inventory, crafting, salvage, health/death/respawn, chat & slash commands', async () => {
    const c1 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__craft_world?sessionId=s3');
    await flushAsync();
    const world = await engine.getOrCreateWorld('craft_world');
    const sess = Array.from(world.sessions.values())[0];

    // Craft 2x Maple Wood Planks (each craft turns 1 Maple Log -> 4 Planks; 2 crafts = 8 Planks, 6 Logs left)
    c1.sendC2S(117, { VK: 'Maple Wood Planks', sK: 0, jsF: 2 });
    await flushAsync();
    let inv = JSON.parse(c1.lastMsg(91).L_.N_);
    assert.equal(inv[2].amount, 6, '2 Maple Logs consumed');
    const plankSlot = inv.findIndex((s) => s && s.name === 'Maple Wood Planks');
    assert.ok(plankSlot >= 0 && inv[plankSlot].amount === 8, '8 Maple Wood Planks crafted');

    // Craft failure when missing ingredients (Diamond Pickaxe)
    c1.sendC2S(117, { VK: 'Diamond Pickaxe', sK: 0, jsF: 1 });
    await flushAsync();
    inv = JSON.parse(c1.lastMsg(91).L_.N_);
    assert.equal(inv.some((s) => s && s.name === 'Diamond Pickaxe'), false, 'Missing ingredients rejected');

    // Salvage 4 Maple Wood Planks -> returns 1 Maple Log
    c1.sendC2S(10, { Om: plankSlot, Qm: 4 });
    await flushAsync();
    inv = JSON.parse(c1.lastMsg(91).L_.N_);
    assert.equal(inv[plankSlot].amount, 4, '4 Planks salvaged');
    assert.equal(inv[2].amount, 7, '1 Maple Log returned by salvage');

    // Swap slots 0 and 1, drop 2 items from slot 0
    c1.sendC2S(76, { gH: 0, Hs: 1 });
    c1.sendC2S(115, { qH: 0, IH: 2, Jk: 0 });
    await flushAsync();
    inv = JSON.parse(c1.lastMsg(91).L_.N_);
    assert.equal(inv[0].name, 'Dirt');
    assert.equal(inv[0].amount, 30);

    // Health, lethal damage, movement lock while dead, respawn
    engine.applyDamage(world, sess, 40);
    await flushAsync();
    assert.equal(c1.lastMsg(107).Y5, 0.6);
    engine.applyDamage(world, sess, 100);
    await flushAsync();
    assert.equal(c1.entitySettings['1']._isAlive, false);
    const deadX = sess.x;
    c1.sendC2S(111, {
      Av: 25, Tv: -2, Qv: 25, RP: 0, loF: 0, FQ: 0,
      yR: false, kR: false, xR: 0, jR: false,
      vR: [25, -2, 25], bR: [0, 0, 0], gR: 9, HQ: [0, 0, 0], EQ: false, pY: 'normal',
    });
    await flushAsync();
    assert.equal(sess.x, deadX, 'Movement locked while dead');

    c1.sendC2S(15, undefined); // Respawn Ki=15
    await flushAsync();
    assert.equal(c1.entitySettings['1']._isAlive, true);
    assert.equal(c1.lastMsg(107).Y5, 1.0);
    assert.deepEqual([sess.x, sess.y, sess.z], [16.5, -2, 16.5]);

    // Chat and commands: /help, /time, /seed, /give, /clear, /spawn
    c1.sendC2S(38, { Nk: 'Hello local survival!', Zm: null });
    c1.sendC2S(38, { Nk: '/help', Zm: null });
    c1.sendC2S(38, { Nk: '/time 18000', Zm: null });
    c1.sendC2S(38, { Nk: '/seed', Zm: null });
    c1.sendC2S(38, { Nk: '/clear', Zm: null });
    c1.sendC2S(38, { Nk: '/give Bread 5', Zm: null });
    c1.sendC2S(38, { Nk: '/spawn', Zm: null });
    await flushAsync();
    const chats = (c1.messagesByOpcode.get(125) || []).map((m) => m.content);
    assert.ok(chats.includes('Hello local survival!'));
    assert.ok(chats.some((t) => t.includes('18000')));
    assert.ok(chats.some((t) => t.includes('fable_1')));
    assert.ok(chats.some((t) => t.includes('Gave 5x Bread')));
    inv = JSON.parse(c1.lastMsg(91).L_.N_);
    assert.equal(inv[0].name, 'Bread');
    assert.equal(inv[0].amount, 5);
    c1.ws.close();
    await flushAsync();
  });

  await check('5. usernames (Unicode/emoji/symbols/spaces) & full reload persistence + multi-socket isolation', async () => {
    const namesToTest = [
      'Olena Masian',
      'Олена',
      '名前',
      'имя✨',
      'user name!',
      '@admin#1',
      '😀玩家',
      '!@#$%^&*()[]{}<>?',
    ];
    for (const sample of namesToTest) {
      const out = engine.setPlayerName(sample);
      assert.equal(out, sample, `Exact preservation failed for ${sample}`);
    }

    // Connect two clients to surv_world, verify both isolated entities, then reload a brand-new engine from backingMap
    const cA = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__surv_world?sessionId=reA');
    const cB = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__surv_world?sessionId=reB');
    await flushAsync();
    assert.equal(cA.state.entities.get('1').name, '!@#$%^&*()[]{}<>?');
    assert.equal(cA.state.entities.size, 2, 'Two isolated sockets present');
    cB.ws.close();
    cA.ws.close();
    await flushAsync();

    // Fresh engine reload using sharedBacking
    const reloadedEngine = await engineMod.createLocalBloxdEngine({
      protocolMap: protocol,
      worldMeta,
      backingMap: sharedBacking,
      initialPlayerName: 'имя✨',
    });
    const cReload = new TestRoomClient(reloadedEngine, 'wss://game-server-local1.bloxd.io/game_room_classic__surv_world?sessionId=reC');
    await flushAsync();
    assert.equal(cReload.state.entities.get('1').name, 'имя✨');
    const reloadedWorld = await reloadedEngine.getOrCreateWorld('surv_world');
    assert.equal(reloadedEngine.getWorldBlock(reloadedWorld, 18, -4, 16), 2, 'Placed Dirt persisted across engine reload');
    assert.equal(reloadedEngine.getWorldBlock(reloadedWorld, 19, -3, 16), 2, 'Second block override persisted across reload');
    cReload.ws.close();
    await flushAsync();
  });

  await check('6. Code Blocks, lobby files, permissions, revision conflicts, and 2-client collaboration', async () => {
    assert.equal(engineMod.makeLobbyDocKey('folder\\sub/util.js'), 'lobby:folder/sub/util.js');
    assert.equal(engineMod.makeCodeBlockDocKey(4, -3, 9), 'codeBlock:4,-3,9');
    assert.throws(() => engineMod.makeLobbyDocKey('../secret.js'), /traversal/);
    assert.throws(() => engineMod.makeLobbyDocKey(''), /empty/);
    assert.throws(() => engineMod.makeLobbyDocKey('a'.repeat(70)), /Unsafe/);

    // HTTP-equivalent local Code Block & Lobby APIs
    const cbInit = await engine.codeApi.getCodeBlock({ worldId: 'code_world', x: 10, y: -3, z: 10 });
    assert.equal(cbInit.docKey, 'codeBlock:10,-3,10');
    assert.equal(cbInit.revision, 0);

    const unicodeSrc = '// Привіт світ ✨ 名前\napi.sendMessage(myId, "Hello 😀!");\n';
    const push1 = await engine.codeApi.collabPush({
      worldId: 'code_world',
      docKey: 'codeBlock:10,-3,10',
      code: unicodeSrc,
      baseRevision: 0,
      clientID: 'cliA',
    });
    assert.equal(push1.accepted, true);
    assert.equal(push1.revision, 1);

    // Stale revision write must be rejected
    const stale = await engine.codeApi.collabPush({
      worldId: 'code_world',
      docKey: 'codeBlock:10,-3,10',
      code: '// overwrite',
      baseRevision: 0,
      clientID: 'cliB',
    });
    assert.equal(stale.accepted, false);
    assert.equal(stale.conflict, true);

    const pull1 = await engine.codeApi.collabPull({ worldId: 'code_world', docKey: 'codeBlock:10,-3,10' });
    assert.equal(pull1.code, unicodeSrc);
    assert.equal(pull1.revision, 1);

    // Permissions: read-only & hidden
    await engine.codeApi.setDocPermissions('code_world', 'lobby:readonly.js', { canSeeCode: true, canEditCode: false });
    const roWrite = await engine.codeApi.collabPush({ worldId: 'code_world', docKey: 'lobby:readonly.js', code: 'x=1', baseRevision: 0 });
    assert.equal(roWrite.accepted, false);

    await engine.codeApi.setDocPermissions('code_world', 'lobby:secret.js', { canSeeCode: false, canEditCode: false });
    const hiddenRead = await engine.codeApi.getLobbyCode({ worldId: 'code_world', path: 'secret.js' });
    assert.equal(hiddenRead.canSeeCode, false);
    assert.equal(hiddenRead.code, null);

    // Two live clients collaborating over opcode 2 -> S2C 94 (Join, Push, Cursor, CreateFile, RenameFile, DeleteFile)
    const peer1 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__code_world?sessionId=p1');
    const peer2 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__code_world?sessionId=p2');
    await flushAsync();

    peer1.sendC2S(2, { Qr: 0, Kr: 'codeBlock:10,-3,10' }); // Join
    peer2.sendC2S(2, { Qr: 0, Kr: 'codeBlock:10,-3,10' }); // Join
    await flushAsync();

    peer1.sendC2S(2, { Qr: 2, Kr: 'codeBlock:10,-3,10', Al: 1, Tl: unicodeSrc + '// v2\n' }); // Push
    peer1.sendC2S(2, { Qr: 3, Kr: 'codeBlock:10,-3,10', Bl: 12, Ul: 5 }); // Cursor
    peer1.sendC2S(2, { Qr: 6, Kr: 'lobby:helper.js', Yu: 'helper.js' }); // CreateFile
    peer1.sendC2S(2, { Qr: 7, Kr: 'lobby:helper.js', Yu: 'utils.js' }); // RenameFile
    await flushAsync();

    const p2Events = peer2.messagesByOpcode.get(94) || [];
    assert.ok(p2Events.some((e) => e.Qr === 1 && e.Al === 2 && e.Tl.endsWith('// v2\n')), 'Peer 2 received live code push');
    assert.ok(p2Events.some((e) => e.Qr === 3 && e.Bl === 12 && e.Ul === 5), 'Peer 2 received remote cursor');
    const lobbyFiles = await engine.codeApi.getLobbyCode({ worldId: 'code_world', path: 'main.js' });
    assert.ok(Object.prototype.hasOwnProperty.call(lobbyFiles.files, 'utils.js'), 'Renamed lobby file exists');

    peer1.sendC2S(2, { Qr: 8, Kr: 'lobby:utils.js' }); // DeleteFile
    await flushAsync();
    const lobbyAfterDel = await engine.codeApi.getLobbyCode({ worldId: 'code_world', path: 'main.js' });
    assert.equal(Object.prototype.hasOwnProperty.call(lobbyAfterDel.files, 'utils.js'), false, 'Deleted lobby file removed');

    peer1.ws.close();
    peer2.ws.close();
    await flushAsync();
  });

  await check('7. live captured module 2557 cipher (9445 + qE vectors), XOR-42 disambiguation, and malformed frame safety', async () => {
    const vm = require('vm');
    const mainBundlePath = path.join(ROOT, 'static', 'js', '7rp1w.main.57cd8edd.js');
    const mainSrc = fs.readFileSync(mainBundlePath, 'utf8');
    const modStart = mainSrc.indexOf('(FF,pF,DF)=>{DF.r(pF),DF.d(pF,{butto:');
    assert.ok(modStart > 0, 'Located module 2557 in captured main bundle');
    const braceStart = mainSrc.indexOf('{', mainSrc.indexOf('=>', modStart));
    let depth = 0;
    let inStr = null;
    let esc = false;
    let modEnd = braceStart;
    for (let i = braceStart; i < mainSrc.length; i++) {
      const ch = mainSrc[i];
      if (inStr) {
        if (esc) esc = false;
        else if (ch === '\\\\') esc = true;
        else if (ch === inStr) inStr = null;
        continue;
      }
      if (ch === '"' || ch === "\x27" || ch === '\x60') { inStr = ch; continue; }
      if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) { modEnd = i + 1; break; }
      }
    }
    const modBody = mainSrc
      .slice(modStart, modEnd)
      .replace('const Tp=ap;', 'pF.__cipher={get PF(){return PF},get oF(){return oF},get xp(){return xp}};const Tp=ap;');

    const sandbox = {
      Buffer, Uint8Array, Uint16Array, Uint32Array, Int32Array, Float32Array, Float64Array,
      Uint8ClampedArray, ArrayBuffer, DataView, TextEncoder, TextDecoder, URL, Math, Date,
      Object, Array, String, Number, Boolean, Symbol, RegExp, Error, TypeError, Map, Set, WeakMap, Proxy, Reflect, Promise,
      console,
      performance: { now: () => 1000 },
      setInterval: () => 1, clearInterval: () => {}, setTimeout: () => 1, clearTimeout: () => {},
      navigator: { userAgent: 'Mozilla/5.0', productSub: '20030107', webdriver: false, languages: ['en-US'] },
      document: { createElement: () => ({}), documentElement: {}, body: {}, all: {} },
    };
    sandbox.window = sandbox; sandbox.self = sandbox; sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    const modFn = vm.runInContext('(' + modBody + ')', sandbox);
    const modExports = {};
    const makeStub = () => new Proxy(function () { return makeStub(); }, {
      get: (_t, p) => (p === Symbol.toPrimitive ? () => 0 : makeStub()),
    });
    function webpackReq(id) {
      if (id === 509) return { b: 856 };
      if (id === 2571) return new Proxy({}, { get: () => () => {} });
      if (id === 2615) return protocol.opcodes || {};
      if (id === 2593) return { b: (k) => { sandbox[k] = 'ok'; }, e: () => 0, g: 'ok' };
      if (id === 2606) return { d: () => {}, h: () => {} };
      if (id === 28) return { h: {}, n: { TA: () => {} } };
      return makeStub();
    }
    webpackReq.r = () => {};
    webpackReq.d = (t, def) => {
      for (const k of Object.keys(def)) Object.defineProperty(t, k, { enumerable: true, get: def[k] });
    };
    modFn({ exports: modExports }, modExports, webpackReq);
    const { PF: liveBytesCipher, oF: liveOpcodeCipher, xp: setLiveAdjustments } = modExports.__cipher;

    function buildLiveCapturedFrame(opcode, payload) {
      const codec = engine.c2sCodecs.get(String(opcode));
      const encOp = liveOpcodeCipher(opcode);
      const hdrTmp = new Uint8Array(9);
      const it = { offset: 0 };
      engine.rt.schema.encode.number(hdrTmp, encOp, it);
      const hdr = hdrTmp.subarray(0, it.offset);
      if (codec) {
        const plain = Uint8Array.from(codec.encode(payload));
        liveBytesCipher(plain);
        const frame = new Uint8Array(1 + hdr.length + plain.length);
        frame[0] = engine.PROTOCOL.ROOM_DATA_BYTES;
        frame.set(hdr, 1);
        frame.set(plain, 1 + hdr.length);
        return frame;
      }
      const body = payload === undefined ? new Uint8Array(0) : Uint8Array.from(engine.rt.msgpack.pack(payload));
      const frame = new Uint8Array(1 + hdr.length + body.length);
      frame[0] = engine.PROTOCOL.ROOM_DATA;
      frame.set(hdr, 1);
      if (body.length > 0) frame.set(body, 1 + hdr.length);
      return frame;
    }

    const adjVectors = [
      [],
      [0],
      [7],
      [5, 9],
      [1, 2, 3],
      [10, 20, 30, 40],
      [11, 22, 33, 44, 55, 66, 77, 88],
      Array.from({ length: 16 }, (_, i) => (i * 17 + 3) & 0xff),
      Array.from({ length: 64 }, (_, i) => (i * 29 + 11) & 0xff),
    ];

    const sampleMessages = [
      [103, { iH: '0|-1|0' }],
      [43, { bV: [17, -4, 16], jG: 2, zV: 'Dirt', mV: false, tV: false, qV: false }], // collides with 1 under XOR-42
      [2, { Qr: 0, Kr: 'lobby:main.js', Al: null, Tl: null, Bl: null, Ul: null, hu: null, Yu: null, Xu: null }], // collides with 40
      [35, { oV: 4, RV: 4 }], // collides with 9
      [9, { rK: 1, bK: 2, zK: 4, EM: null }], // collides with 35
      [76, { gH: 0, Hs: 1 }], // collides with 102
      [93, 4], // collides with 119
      [117, { VK: 'Maple Planks', sK: 0, jsF: 1 }], // collides with 95
      [38, { Nk: 'Привіт cipher ✨', Zm: null }],
      [15, undefined], // ROOM_DATA respawn, collides with 37
    ];

    for (const adj of adjVectors) {
      setLiveAdjustments(adj);
      for (const [opcode, payload] of sampleMessages) {
        const liveWire = buildLiveCapturedFrame(opcode, payload);
        const engineWire = engine.encodeClientMessage(opcode, payload, 'captured_js', adj);
        assert.deepEqual(Buffer.from(engineWire), Buffer.from(liveWire), `Engine captured_js frame matches live module 2557 for op=${opcode} adjLen=${adj.length}`);

        const decJs = engine.decodeClientWireFrame(liveWire, { adjustments: adj });
        assert.equal(decJs.kind, 'message', `Decoded live frame op=${opcode} adjLen=${adj.length}`);
        assert.equal(decJs.opcode, opcode, `Disambiguated live JS op=${opcode} adjLen=${adj.length}`);
        assert.equal(decJs.cipherMode, 'js9445');

        const wasmWire = engine.encodeClientMessage(opcode, payload, true, adj);
        const decWasm = engine.decodeClientWireFrame(wasmWire, { adjustments: adj, detectedJsCipher: opcode === 93 ? false : null });
        assert.equal(decWasm.kind, 'message');
        assert.equal(decWasm.opcode, opcode, `Disambiguated raw WASM op=${opcode} adjLen=${adj.length}`);
        assert.equal(decWasm.cipherMode, 'wasm9423');
      }
    }

    // End-to-end LocalWebSocket session using live captured 9445 + non-empty qE adjustments + malformed frame resilience
    const adjEngine = await engineMod.createLocalBloxdEngine({
      protocolMap: protocol,
      worldMeta,
      backingMap: new Map(),
      initialAdjustments: [13, 27, 41, 55, 69, 83, 97, 111],
    });
    const jsClient = new TestRoomClient(adjEngine, 'wss://game-server-local1.bloxd.io/game_room_classic__cipher_world?sessionId=jslive');
    await flushAsync();
    const tbMsg = (jsClient.messagesByOpcode.get(64) || [])[0];
    assert.deepEqual(Array.from(tbMsg.qE), [13, 27, 41, 55, 69, 83, 97, 111], 'Tb=64 delivered qE adjustment vector');

    setLiveAdjustments(Array.from(tbMsg.qE));
    // Malformed / truncated frames must not crash or close the room
    jsClient.ws.send(new Uint8Array([17])); // truncated ROOM_DATA_BYTES
    jsClient.ws.send(new Uint8Array([13])); // truncated ROOM_DATA
    jsClient.ws.send(new Uint8Array([17, 0xcc, 0x24, 0xff, 0xff, 0xff])); // invalid Avro varint under cipher
    await flushAsync();
    assert.equal(jsClient.ws.readyState, adjEngine.LocalWebSocket.OPEN, 'Socket remains OPEN after malformed frames');

    // Send real captured-JS chunk request + selected slot (including slot 21 XOR-42 edge case after cipher lock!)
    const prevChunks = (jsClient.messagesByOpcode.get(24) || []).length;
    const enc103Op = liveOpcodeCipher(103);
    const h103 = new Uint8Array(9); const i103 = { offset: 0 };
    adjEngine.rt.schema.encode.number(h103, enc103Op, i103);
    const p103 = Uint8Array.from(adjEngine.c2sCodecs.get('103').encode({ iH: '0|-1|0' }));
    liveBytesCipher(p103);
    const f103 = new Uint8Array(1 + i103.offset + p103.length);
    f103[0] = 17; f103.set(h103.subarray(0, i103.offset), 1); f103.set(p103, 1 + i103.offset);
    jsClient.ws.send(f103);

    jsClient.ws.send(adjEngine.encodeClientMessage(93, 21, 'captured_js', Array.from(tbMsg.qE)));
    await flushAsync();
    assert.equal((jsClient.messagesByOpcode.get(24) || []).length, prevChunks + 1, 'Chunk delivered for live 9445+qE request');
    const slotAcks = jsClient.messagesByOpcode.get(105) || [];
    assert.equal(slotAcks[slotAcks.length - 1], 21, 'Slot 21 disambiguated cleanly after session cipher lock');
    jsClient.ws.close();
    await flushAsync();
  });

  await check('8. CodeMirror 6 ChangeSet.toJSON() applier, Chunk 82 path rules, and captured collab JSON wire compatibility', async () => {
    // Chunk 82 strict lobby path validator
    assert.equal(engineMod.isValidCapturedLobbyPath('main.js'), true);
    assert.equal(engineMod.isValidCapturedLobbyPath('plugins/combat/helper.ts'), true);
    assert.equal(engineMod.isValidCapturedLobbyPath('types/api.d.ts'), true);
    assert.equal(engineMod.isValidCapturedLobbyPath('vendor/app.min.js'), false);
    assert.equal(engineMod.isValidCapturedLobbyPath('dist/game.bundle.js'), false);
    assert.equal(engineMod.isValidCapturedLobbyPath('/abs.js'), false);
    assert.equal(engineMod.isValidCapturedLobbyPath('win\\path.js'), false);
    assert.equal(engineMod.isValidCapturedLobbyPath(' main.js'), false);
    assert.equal(engineMod.isValidCapturedLobbyPath('notes.txt'), false);
    assert.throws(() => engineMod.makeLobbyDocKey('bad.min.js'), /extension/);
    assert.throws(() => engineMod.makeLobbyDocKey('bad.bundle.js'), /extension/);
    assert.throws(() => engineMod.makeLobbyDocKey('readme.md'), /extension/);

    // CodeMirror 6 ChangeSet.toJSON() incremental edits including emoji surrogate pairs & multi-line
    const baseDoc = 'const greet = "Привіт 😀";\nconsole.log(greet);\n';
    // Replace "Привіт 😀" with "Світ ✨" and append a second line
    const target = '"Привіт 😀"';
    const from = baseDoc.indexOf(target);
    const to = from + target.length;
    const tail = baseDoc.length - to;
    const cmChange1 = [from, [target.length, '"Світ ✨"'], tail];
    const step1 = engineMod.applyCodeMirrorChangeSet(baseDoc, cmChange1);
    assert.equal(step1, 'const greet = "Світ ✨";\nconsole.log(greet);\n');

    // Append two lines at end: retain step1.length, insert [0, "const x = 42;", "export { x };", ""]
    const cmChange2 = [step1.length, [0, 'const x = 42;', 'export { x };', '']];
    const step2 = engineMod.applyCodeMirrorChangeSet(step1, cmChange2);
    assert.ok(step2.endsWith('const x = 42;\nexport { x };\n'));

    // Reject out-of-range / length-mismatch ChangeSets safely
    assert.throws(() => engineMod.applyCodeMirrorChangeSet(step2, [step2.length + 5]), /bounds/);
    assert.throws(() => engineMod.applyCodeMirrorChangeSet(step2, [2]), /mismatch/);

    // HTTP-equivalent collabPush with captured updates JSON array
    await engine.codeApi.collabPush({
      worldId: 'cm_world',
      docKey: 'lobby:main.js',
      code: baseDoc,
      version: 0,
      clientID: 'seedCli',
    });
    const batchUpdates = JSON.stringify([
      { clientID: 'cmHttp', changes: cmChange1 },
      { clientID: 'cmHttp', changes: cmChange2 },
    ]);
    const pushRes = await engine.codeApi.collabPush({
      worldId: 'cm_world',
      docKey: 'lobby:main.js',
      version: 1,
      updates: batchUpdates,
      clientID: 'cmHttp',
    });
    assert.equal(pushRes.accepted, true);
    assert.equal(pushRes.revision, 3, 'Two CM6 updates incremented revision from 1 to 3');
    assert.equal(pushRes.code, step2);

    // Live WebSocket captured-style Join + CM6 Push + Chunk 82 two-arg RenameFile/RenameFolder/DeleteFile
    const c1 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__cm_world?sessionId=cm1');
    const c2 = new TestRoomClient(engine, 'wss://game-server-local1.bloxd.io/game_room_classic__cm_world?sessionId=cm2');
    await flushAsync();

    c1.sendC2S(2, { Qr: 0, Kr: 'lobby:main.js' });
    c2.sendC2S(2, { Qr: 0, Kr: 'lobby:main.js' });
    await flushAsync();

    const joinEvt = (c1.messagesByOpcode.get(94) || []).find((e) => e.Qr === 0);
    assert.ok(Array.isArray(JSON.parse(joinEvt.Mq)), 'Chunk 82 Joined Mq parses as JSON peer array');
    assert.ok(Array.isArray(JSON.parse(joinEvt.g6)), 'Chunk 82 Joined g6 parses as JSON cursors array');

    const wsUpdateJson = JSON.stringify([{ clientID: 'cmWs1', changes: [step2.length, [0, '// live tail', '']] }]);
    c1.sendC2S(2, { Qr: 2, Kr: 'lobby:main.js', Al: 3, Tl: wsUpdateJson });
    c1.sendC2S(2, { Qr: 6, Kr: 'lobby:main.js', Yu: 'mods/a.ts' });
    c1.sendC2S(2, { Qr: 7, Kr: 'lobby:main.js', Yu: 'mods/a.ts', Xu: 'mods/b.ts' });
    c1.sendC2S(2, { Qr: 10, Kr: 'lobby:main.js', Yu: 'mods', Xu: 'plugins' });
    await flushAsync();

    const c2Collab = c2.messagesByOpcode.get(94) || [];
    const updEvt = c2Collab.find((e) => e.Qr === 1 && e.Al === 4);
    assert.ok(updEvt, 'Peer 2 received revision 4 CM6 update');
    assert.equal(JSON.parse(updEvt.Tl)[0].clientID, 'cmWs1', 'Updates Tl carries valid JSON updates array');
    const filesEvt = c2Collab.filter((e) => e.Qr === 5).pop();
    assert.ok(Object.prototype.hasOwnProperty.call(JSON.parse(filesEvt.f6), 'plugins/b.ts'), 'Chunk 82 LobbyFilesChanged f6 map has renamed folder/file');

    const lobbyCheck = await engine.codeApi.getLobbyCode({ worldId: 'cm_world', path: 'main.js' });
    assert.ok(lobbyCheck.code.endsWith('// live tail\n'));
    assert.ok(Object.prototype.hasOwnProperty.call(lobbyCheck.files, 'plugins/b.ts'));

    c1.sendC2S(2, { Qr: 8, Kr: 'lobby:main.js', Yu: 'plugins/b.ts' });
    await flushAsync();
    const afterDel = await engine.codeApi.getLobbyCode({ worldId: 'cm_world', path: 'main.js' });
    assert.equal(Object.prototype.hasOwnProperty.call(afterDel.files, 'plugins/b.ts'), false, 'Two-arg DeleteFile removed plugins/b.ts');

    c1.ws.close();
    c2.ws.close();
    await flushAsync();
  });


  await check('9. Chunk 117 Avro MetricsEnvelope shim, POST staging (no window.fetch override), and Chunk 77 Unicode code-point limits', async () => {
    const c117Path = path.join(ROOT, 'static', 'js', '7rp1w.117.2c551f46.chunk.js');
    assert.ok(fs.existsSync(c117Path), '7rp1w.117.2c551f46.chunk.js exists');
    const c117Mod = await import('file://' + c117Path);
    assert.equal(c117Mod.id, 117, 'Chunk 117 exports id=117');
    assert.equal(typeof c117Mod.modules[6718], 'function', 'Chunk 117 exports module 6718');

    const modExports = {};
    const fakeDF = {
      r: () => {},
      d: (target, defs) => {
        for (const [k, fn] of Object.entries(defs)) {
          Object.defineProperty(target, k, { enumerable: true, get: fn });
        }
      },
    };
    c117Mod.modules[6718]({}, modExports, fakeDF);
    assert.equal(typeof modExports.encodeBinaryMessageWithMetricsCookies, 'function');

    // Exact Apache Avro binary decoder for { metricsCookies: map<string>, contents: bytes }
    function decodeAvroEnvelope(buf) {
      let off = 0;
      function readZigZag() {
        let res = 0n, shift = 0n;
        while (true) {
          assert.ok(off < buf.length, 'Avro varint in bounds');
          const b = buf[off++];
          res |= BigInt(b & 0x7f) << shift;
          if ((b & 0x80) === 0) break;
          shift += 7n;
        }
        return Number((res >> 1n) ^ -(res & 1n));
      }
      function readBytes() {
        const len = readZigZag();
        assert.ok(len >= 0 && off + len <= buf.length, 'Avro bytes length in bounds');
        const slice = buf.subarray(off, off + len);
        off += len;
        return slice;
      }
      function readString() {
        return Buffer.from(readBytes()).toString('utf8');
      }
      const metricsCookies = {};
      while (true) {
        let count = readZigZag();
        if (count === 0) break;
        if (count < 0) { count = -count; readZigZag(); }
        for (let i = 0; i < count; i++) {
          const k = readString();
          const v = readString();
          metricsCookies[k] = v;
        }
      }
      const contents = readBytes();
      assert.equal(off, buf.length, 'No trailing Avro bytes');
      return { metricsCookies, contents };
    }

    const innerBytes = Uint8Array.from([0x04, 0x68, 0x69, 0x00, 0xff, 0x7f]);
    const encoded = modExports.encodeBinaryMessageWithMetricsCookies({
      metricsCookies: { '3PSIDMCPP': 'tok_abc', user: '🔥世界 Player' },
      contents: innerBytes,
    });
    assert.ok(encoded instanceof Uint8Array, 'Encoded envelope is Uint8Array');
    const b64 = encoded.toString('base64');
    const fromB64 = Buffer.from(b64, 'base64');
    assert.deepEqual(Array.from(fromB64), Array.from(encoded), 'Base64 output matches raw Uint8Array bytes');
    const decoded = decodeAvroEnvelope(fromB64);
    assert.deepEqual(decoded.metricsCookies, { '3PSIDMCPP': 'tok_abc', user: '🔥世界 Player' });
    assert.deepEqual(Array.from(decoded.contents), Array.from(innerBytes));

    // Empty cookies & empty contents case
    const emptyEncoded = modExports.encodeBinaryMessageWithMetricsCookies({ metricsCookies: {} });
    const emptyDecoded = decodeAvroEnvelope(Buffer.from(emptyEncoded.toString('base64'), 'base64'));
    assert.deepEqual(emptyDecoded.metricsCookies, {});
    assert.equal(emptyDecoded.contents.length, 0);

    // Verify local_bloxd_engine.js never overrides window.fetch
    const engSrc = fs.readFileSync(path.join(ROOT, 'local_bloxd_engine.js'), 'utf8');
    assert.equal(/window\.fetch\s*=/.test(engSrc), false, 'local_bloxd_engine.js must never override window.fetch');
    assert.equal(/globalThis\.fetch\s*=/.test(engSrc), false, 'local_bloxd_engine.js must never override globalThis.fetch');

    // Verify Module 1386 stages POST bodies via X-Fable-Post-Id and Chunk 77 uses code-point length
    const mainSrc = fs.readFileSync(path.join(ROOT, 'static', 'js', '7rp1w.main.57cd8edd.js'), 'utf8');
    assert.ok(mainSrc.includes('X-Fable-Post-Id'), 'Module 1386 stages POST bodies with X-Fable-Post-Id');
    const c77Src = fs.readFileSync(path.join(ROOT, 'static', 'js', '7rp1w.77.ad1d9852.chunk.js'), 'utf8');
    assert.ok(c77Src.includes('Array.from(UF).length>RF.f'), 'Chunk 77 measures username length in Unicode code points');
    const c156Src = fs.readFileSync(path.join(ROOT, 'static', 'js', '7rp1w.156.69c43f4d.chunk.js'), 'utf8');
    assert.ok(c156Src.includes('X-Fable-Post-Id'), 'Chunk 156 stages Colyseus POST bodies with X-Fable-Post-Id');
  });

  await check('10. Native Chunk32 RLE bridge fast path, Unicode room URL decoding, and syncCodeDocFull propagation', async () => {
    let nativeChunkCalls = 0;
    const syncedDocs = [];
    const mockNativeBridge = {
      getPlayerName: () => 'Native🚀Hero',
      setPlayerName: () => {},
      loadWorldBundleJson: () => '{}',
      saveWorldMetaJson: () => {},
      setWorldBlockEdit: () => {},
      syncCodeDocFull: (world, docKey, code, version, canSee, canEdit, eng) => {
        syncedDocs.push({ world, docKey, code, version, canSee, canEdit, eng });
      },
      deleteCodeDoc: () => {},
      generateChunk32RleBase64: (cx, cy, cz, seedI32, _modeId) => {
        nativeChunkCalls++;
        const vox = engineMod.generateChunk32(cx, cy, cz, seedI32 >>> 0, null);
        const rle = engineMod.rleEncode(vox);
        return Buffer.from(rle).toString('base64');
      },
    };

    const memStore = new Map();
    const persistence = {
      async get(k) { return memStore.has(k) ? JSON.parse(memStore.get(k)) : null; },
      async set(k, v) { memStore.set(k, JSON.stringify(v)); },
    };
    const eng = await engineMod.createLocalBloxdEngine({
      protocolMap: protocol,
      worldMeta,
      persistence,
      nativeBridge: mockNativeBridge,
    });

    // Unicode percent-encoded room URL (matches LocalBloxdProtocolCore.encodeUrlSegment)
    const unicodeWorld = '世界 🎮 / Room #1';
    const encWorld = encodeURIComponent(unicodeWorld);
    const client = new TestRoomClient(eng, `wss://game-server-local1.bloxd.io/game_room_classic__${encWorld}?sessionId=u1`);
    await flushAsync();
    assert.ok(client.hasJoined, 'Joined Unicode room');

    // 1) Clean chunk request uses native generateChunk32RleBase64 fast path
    client.sendC2S(103, { iH: '0|-1|0' });
    await flushAsync();
    assert.equal(nativeChunkCalls, 1, 'Native generateChunk32RleBase64 called for clean chunk');
    const chunkMsg1 = (client.messagesByOpcode.get(24) || []).find((m) => m.W9 === '0|-1|0');
    assert.ok(chunkMsg1 && chunkMsg1.q_ && chunkMsg1.q_.length > 0, 'Received native RLE chunk');
    const seedU32 = engineMod.seedStringToUint32(worldMeta.defaultSeed || 'fable_1');
    const jsExpected1 = engineMod.rleEncode(engineMod.generateChunk32(0, -1, 0, seedU32, null));
    assert.deepEqual(Array.from(chunkMsg1.q_), Array.from(jsExpected1), 'Native RLE chunk matches JS bit-for-bit');

    // 2) Overridden chunk overlays edits cleanly on top of native RLE base
    const overrides = new Map([['16,-4,16', 45]]);
    const overriddenRle = engineMod.buildChunk32Rle(0, -1, 0, seedU32, overrides, mockNativeBridge);
    assert.equal(nativeChunkCalls, 2, 'Native generateChunk32RleBase64 called for overridden chunk');
    const decodedOver = engineMod.rleDecode(overriddenRle, 32768);
    assert.equal(decodedOver[engineMod.chunkIndex(16, 28, 16)], 45, 'Override block overlaid onto native RLE chunk');

    // 3) Permission updates propagate via syncCodeDocFull under exact decoded Unicode worldId
    await eng.codeApi.setDocPermissions(unicodeWorld, 'lobby:main.js', { canSeeCode: true, canEditCode: false });
    const lastSync = syncedDocs[syncedDocs.length - 1];
    assert.ok(lastSync, 'syncCodeDocFull invoked');
    assert.equal(lastSync.world, unicodeWorld, 'Decoded Unicode worldId propagated to native bridge');
    assert.equal(lastSync.docKey, 'lobby:main.js');
    assert.equal(lastSync.canSee, true);
    assert.equal(lastSync.canEdit, false);

    client.ws.close();
    await flushAsync();
  });

  console.log(`ALL PASS: ${results.length} comprehensive test suites succeeded.`);
}

run().catch((err) => {
  console.error('HARNESS FAILED:', err);
  process.exit(1);
});
