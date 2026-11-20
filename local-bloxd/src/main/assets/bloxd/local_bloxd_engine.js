/* Fable Offline Local Bloxd Engine (v856 protocol + survival + code collab) */
(function (root) {
  'use strict';

  const PROTOCOL = {
    HANDSHAKE: 9,
    JOIN_ROOM: 10,
    ERROR: 11,
    LEAVE_ROOM: 12,
    ROOM_DATA: 13,
    ROOM_STATE: 14,
    ROOM_STATE_PATCH: 15,
    ROOM_DATA_SCHEMA: 16,
    ROOM_DATA_BYTES: 17,
  };

  const CIPHER_VERSION_PARAM = 9423; // 11 * 856 + 7 (raw WASM base)
  const CAPTURED_JS_XOR_MASK = 42;
  const CAPTURED_JS_EFFECTIVE_BASE = CIPHER_VERSION_PARAM ^ CAPTURED_JS_XOR_MASK; // 9445
  const MAX_NAME_CODEPOINTS = 64;
  const INVENTORY_SLOTS = 51;
  const DEFAULT_SPAWN = [16.5, -2, 16.5];

  const textEncoder = new TextEncoder();
  const textDecoder = new TextDecoder();

  function normalizeUsername(raw, fallback) {
    if (typeof raw !== 'string') return fallback || 'Explorer';
    if (raw.length === 0) return fallback || 'Explorer';
    const cps = Array.from(raw);
    return cps.length > MAX_NAME_CODEPOINTS ? cps.slice(0, MAX_NAME_CODEPOINTS).join('') : raw;
  }

  function b64ToUint8Clamped(b64) {
    if (!b64 || typeof b64 !== 'string') return null;
    if (typeof Buffer !== 'undefined') {
      const buf = Buffer.from(b64, 'base64');
      return new Uint8ClampedArray(buf.buffer, buf.byteOffset, buf.byteLength);
    }
    const bin = atob(b64);
    const out = new Uint8ClampedArray(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function prepareAtlasSeed(seedJson) {
    if (!seedJson || typeof seedJson !== 'object') return null;
    const out = {
      atlasCache: seedJson.atlasCache ? {
        identity: seedJson.atlasCache.identity,
        bundle: { ...seedJson.atlasCache.bundle },
      } : null,
      texturePack: seedJson.texturePack ? JSON.parse(JSON.stringify(seedJson.texturePack)) : null,
    };
    if (out.atlasCache && out.atlasCache.bundle) {
      const b = out.atlasCache.bundle;
      if (Array.isArray(b.blockAtlasPixelsB64)) {
        b.blockAtlasPixels = b.blockAtlasPixelsB64.map((entry) => (entry ? b64ToUint8Clamped(entry) : null));
      } else if (!Array.isArray(b.blockAtlasPixels)) {
        b.blockAtlasPixels = [];
      }
    }
    return out;
  }

  async function hasSeededBrowserLocalForage(seedVersion) {
    if (typeof indexedDB === 'undefined') return false;
    return new Promise((resolve) => {
      let settled = false;
      const done = (v) => { if (!settled) { settled = true; resolve(v); } };
      const timer = setTimeout(() => done(false), 1500);
      try {
        const req = indexedDB.open('bloxd_localforage', 2);
        req.onupgradeneeded = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains('keyvaluepairs')) {
            db.createObjectStore('keyvaluepairs');
          }
        };
        req.onerror = () => { clearTimeout(timer); done(false); };
        req.onblocked = () => { clearTimeout(timer); done(false); };
        req.onsuccess = () => {
          try {
            const db = req.result;
            if (!db.objectStoreNames.contains('keyvaluepairs')) {
              db.close();
              clearTimeout(timer);
              return done(false);
            }
            const tx = db.transaction('keyvaluepairs', 'readonly');
            const store = tx.objectStore('keyvaluepairs');
            const getReq = store.get('__fableAtlasSeedVersion');
            getReq.onsuccess = () => {
              const ok = getReq.result === seedVersion;
              db.close();
              clearTimeout(timer);
              done(ok);
            };
            getReq.onerror = () => { db.close(); clearTimeout(timer); done(false); };
          } catch (_) {
            clearTimeout(timer);
            done(false);
          }
        };
      } catch (_) {
        clearTimeout(timer);
        done(false);
      }
    });
  }

  async function seedBrowserLocalForage(preparedSeed, seedVersion) {
    if (!preparedSeed || typeof indexedDB === 'undefined') return false;
    return new Promise((resolve) => {
      let settled = false;
      const done = (v) => { if (!settled) { settled = true; resolve(v); } };
      const timer = setTimeout(() => done(false), 4000);
      try {
        const req = indexedDB.open('bloxd_localforage', 2);
        req.onupgradeneeded = () => {
          const db = req.result;
          if (!db.objectStoreNames.contains('keyvaluepairs')) {
            db.createObjectStore('keyvaluepairs');
          }
        };
        req.onerror = () => { clearTimeout(timer); done(false); };
        req.onblocked = () => { clearTimeout(timer); done(false); };
        req.onsuccess = () => {
          try {
            const db = req.result;
            if (!db.objectStoreNames.contains('keyvaluepairs')) {
              db.close();
              clearTimeout(timer);
              return done(false);
            }
            const tx = db.transaction('keyvaluepairs', 'readwrite');
            const store = tx.objectStore('keyvaluepairs');
            if (preparedSeed.atlasCache) store.put(preparedSeed.atlasCache, 'atlasCache');
            if (preparedSeed.texturePack) store.put(preparedSeed.texturePack, 'texturePack');
            if (seedVersion) store.put(seedVersion, '__fableAtlasSeedVersion');
            tx.oncomplete = () => { db.close(); clearTimeout(timer); done(true); };
            tx.onerror = () => { db.close(); clearTimeout(timer); done(false); };
            tx.onabort = () => { db.close(); clearTimeout(timer); done(false); };
          } catch (_) {
            clearTimeout(timer);
            done(false);
          }
        };
      } catch (_) {
        clearTimeout(timer);
        done(false);
      }
    });
  }

  // ---- Avro Binary Codec (compatible with avsc unwrapped unions) ----
  class AvroWriter {
    constructor(initialSize) {
      this.buf = new Uint8Array(initialSize || 512);
      this.pos = 0;
      this.dv = new DataView(this.buf.buffer);
    }
    ensure(need) {
      if (this.pos + need <= this.buf.length) return;
      let nextLen = this.buf.length * 2;
      while (nextLen < this.pos + need) nextLen *= 2;
      const next = new Uint8Array(nextLen);
      next.set(this.buf.subarray(0, this.pos));
      this.buf = next;
      this.dv = new DataView(this.buf.buffer);
    }
    writeByte(b) {
      this.ensure(1);
      this.buf[this.pos++] = b & 0xff;
    }
    writeLong(num) {
      let n = BigInt(typeof num === 'bigint' ? num : Math.trunc(Number(num) || 0));
      let z = (n << 1n) ^ (n >> 63n);
      this.ensure(12);
      while (z >= 0x80n) {
        this.buf[this.pos++] = Number((z & 0x7fn) | 0x80n);
        z >>= 7n;
      }
      this.buf[this.pos++] = Number(z);
    }
    writeInt(num) {
      this.writeLong(num | 0);
    }
    writeFloat(v) {
      this.ensure(4);
      this.dv.setFloat32(this.pos, Number(v) || 0, true);
      this.pos += 4;
    }
    writeDouble(v) {
      this.ensure(8);
      this.dv.setFloat64(this.pos, Number(v) || 0, true);
      this.pos += 8;
    }
    writeBytes(bytes) {
      const u8 = bytes instanceof Uint8Array ? bytes : Uint8Array.from(bytes || []);
      this.writeLong(u8.length);
      this.ensure(u8.length);
      this.buf.set(u8, this.pos);
      this.pos += u8.length;
    }
    writeString(str) {
      const u8 = textEncoder.encode(str == null ? '' : String(str));
      this.writeBytes(u8);
    }
    toUint8Array() {
      return this.buf.slice(0, this.pos);
    }
  }

  class AvroReader {
    constructor(u8, offset) {
      this.buf = u8 instanceof Uint8Array ? u8 : new Uint8Array(u8);
      this.pos = offset || 0;
      if (this.pos < 0 || this.pos > this.buf.length) {
        throw new RangeError('Avro initial offset out of bounds');
      }
      this.dv = new DataView(this.buf.buffer, this.buf.byteOffset, this.buf.byteLength);
    }
    readByte() {
      if (this.pos >= this.buf.length) throw new RangeError('Avro truncated byte');
      return this.buf[this.pos++];
    }
    readBoolean() {
      const b = this.readByte();
      if (b !== 0 && b !== 1) throw new RangeError('Avro invalid boolean byte: ' + b);
      return b === 1;
    }
    readBigLong() {
      if (this.pos >= this.buf.length) throw new RangeError('Avro truncated varint');
      let z = 0n;
      let shift = 0n;
      let terminated = false;
      while (this.pos < this.buf.length) {
        const b = BigInt(this.buf[this.pos++]);
        z |= (b & 0x7fn) << shift;
        if ((b & 0x80n) === 0n) {
          terminated = true;
          break;
        }
        shift += 7n;
        if (shift > 70n) throw new RangeError('Avro varint overflow');
      }
      if (!terminated) throw new RangeError('Avro unterminated varint');
      return (z >> 1n) ^ -(z & 1n);
    }
    readLong() {
      return Number(this.readBigLong());
    }
    readInt() {
      const n = Number(this.readBigLong());
      if (!Number.isFinite(n) || n < -2147483648 || n > 2147483647) {
        throw new RangeError('Avro int out of 32-bit range');
      }
      return n | 0;
    }
    readFloat() {
      if (this.pos + 4 > this.buf.length) throw new RangeError('Avro truncated float');
      const v = this.dv.getFloat32(this.pos, true);
      this.pos += 4;
      return v;
    }
    readDouble() {
      if (this.pos + 8 > this.buf.length) throw new RangeError('Avro truncated double');
      const v = this.dv.getFloat64(this.pos, true);
      this.pos += 8;
      return v;
    }
    readBytes() {
      const len = this.readLong();
      if (!Number.isInteger(len) || len < 0 || this.pos + len > this.buf.length) {
        throw new RangeError('Avro invalid bytes length: ' + len);
      }
      if (len === 0) return new Uint8Array(0);
      const out = this.buf.slice(this.pos, this.pos + len);
      this.pos += len;
      return out;
    }
    readString() {
      return textDecoder.decode(this.readBytes());
    }
  }

  function createAvroSchemaCodec(schemaDef) {
    const named = new Map();
    function resolve(s) {
      if (Array.isArray(s)) {
        const branches = s.map(resolve);
        return { kind: 'union', branches };
      }
      if (typeof s === 'string') {
        if (named.has(s)) return named.get(s);
        return { kind: s };
      }
      if (!s || typeof s !== 'object') return { kind: 'null' };
      if (Array.isArray(s.type)) return resolve(s.type);
      if (s.type === 'record' || s.type === 'error') {
        const rec = { kind: 'record', name: s.name || null, fields: [] };
        if (s.name) named.set(s.name, rec);
        rec.fields = (s.fields || []).map((f) => ({
          name: f.name,
          hasDefault: Object.prototype.hasOwnProperty.call(f, 'default'),
          defaultVal: f.default,
          type: resolve(f.type),
        }));
        return rec;
      }
      if (s.type === 'array') return { kind: 'array', items: resolve(s.items) };
      if (s.type === 'map') return { kind: 'map', values: resolve(s.values) };
      if (s.type === 'enum') {
        const en = { kind: 'enum', symbols: s.symbols || [] };
        if (s.name) named.set(s.name, en);
        return en;
      }
      if (s.type === 'fixed') {
        const fx = { kind: 'fixed', size: s.size || 0 };
        if (s.name) named.set(s.name, fx);
        return fx;
      }
      return resolve(s.type);
    }

    function matchesBranch(branch, val) {
      if (val === null || val === undefined) return branch.kind === 'null';
      switch (branch.kind) {
        case 'null': return false;
        case 'boolean': return typeof val === 'boolean';
        case 'int':
        case 'long':
        case 'float':
        case 'double': return typeof val === 'number' || typeof val === 'bigint';
        case 'string': return typeof val === 'string';
        case 'enum': return typeof val === 'string' && branch.symbols.includes(val);
        case 'bytes':
        case 'fixed': return val instanceof Uint8Array || ArrayBuffer.isView(val);
        case 'array': return Array.isArray(val);
        case 'record':
        case 'map': return typeof val === 'object' && !Array.isArray(val) && !(val instanceof Uint8Array);
        default: return false;
      }
    }

    function writeVal(w, node, val) {
      switch (node.kind) {
        case 'null': return;
        case 'boolean': w.writeByte(val ? 1 : 0); return;
        case 'int': w.writeInt(val); return;
        case 'long': w.writeLong(val); return;
        case 'float': w.writeFloat(val); return;
        case 'double': w.writeDouble(val); return;
        case 'bytes': w.writeBytes(val); return;
        case 'string': w.writeString(val); return;
        case 'fixed':
          w.ensure(node.size);
          if (val) w.buf.set(val.subarray(0, node.size), w.pos);
          w.pos += node.size;
          return;
        case 'enum': {
          const idx = Math.max(0, node.symbols.indexOf(val));
          w.writeInt(idx);
          return;
        }
        case 'array': {
          const arr = Array.isArray(val) ? val : [];
          if (arr.length > 0) {
            w.writeLong(arr.length);
            for (let i = 0; i < arr.length; i++) writeVal(w, node.items, arr[i]);
          }
          w.writeByte(0);
          return;
        }
        case 'map': {
          const entries = val && typeof val === 'object' ? Object.entries(val) : [];
          if (entries.length > 0) {
            w.writeLong(entries.length);
            for (const [k, v] of entries) {
              w.writeString(k);
              writeVal(w, node.values, v);
            }
          }
          w.writeByte(0);
          return;
        }
        case 'record': {
          const obj = val || {};
          for (let i = 0; i < node.fields.length; i++) {
            const f = node.fields[i];
            const fv = obj[f.name] !== undefined ? obj[f.name] : (f.hasDefault ? f.defaultVal : null);
            writeVal(w, f.type, fv);
          }
          return;
        }
        case 'union': {
          let idx = node.branches.findIndex((b) => matchesBranch(b, val));
          if (idx < 0 && val && typeof val === 'object') {
            idx = node.branches.findIndex((b) => b.name && Object.prototype.hasOwnProperty.call(val, b.name));
            if (idx >= 0) {
              w.writeInt(idx);
              writeVal(w, node.branches[idx], val[node.branches[idx].name]);
              return;
            }
          }
          if (idx < 0) idx = 0;
          w.writeInt(idx);
          writeVal(w, node.branches[idx], val);
          return;
        }
      }
    }

    function readVal(r, node) {
      switch (node.kind) {
        case 'null': return null;
        case 'boolean': return r.readBoolean();
        case 'int': return r.readInt();
        case 'long': return r.readLong();
        case 'float': return r.readFloat();
        case 'double': return r.readDouble();
        case 'bytes': return r.readBytes();
        case 'string': return r.readString();
        case 'fixed': {
          if (r.pos + node.size > r.buf.length) throw new RangeError('Avro truncated fixed');
          const out = r.buf.slice(r.pos, r.pos + node.size);
          r.pos += node.size;
          return out;
        }
        case 'enum': {
          const symIdx = r.readInt();
          if (symIdx < 0 || symIdx >= node.symbols.length) {
            throw new RangeError('Avro invalid enum symbol index: ' + symIdx);
          }
          return node.symbols[symIdx];
        }
        case 'array': {
          const out = [];
          while (true) {
            let count = r.readLong();
            if (count === 0) break;
            if (count < 0) { count = -count; r.readLong(); }
            if (!Number.isInteger(count) || count < 0 || count > 100000) {
              throw new RangeError('Avro invalid array block count: ' + count);
            }
            for (let i = 0; i < count; i++) out.push(readVal(r, node.items));
          }
          return out;
        }
        case 'map': {
          const out = {};
          while (true) {
            let count = r.readLong();
            if (count === 0) break;
            if (count < 0) { count = -count; r.readLong(); }
            if (!Number.isInteger(count) || count < 0 || count > 100000) {
              throw new RangeError('Avro invalid map block count: ' + count);
            }
            for (let i = 0; i < count; i++) {
              const k = r.readString();
              out[k] = readVal(r, node.values);
            }
          }
          return out;
        }
        case 'record': {
          const out = {};
          for (let i = 0; i < node.fields.length; i++) {
            const f = node.fields[i];
            out[f.name] = readVal(r, f.type);
          }
          return out;
        }
        case 'union': {
          const idx = r.readInt();
          if (idx < 0 || idx >= node.branches.length) {
            throw new RangeError('Avro invalid union branch index: ' + idx);
          }
          return readVal(r, node.branches[idx]);
        }
        default:
          return null;
      }
    }

    const rootNode = resolve(schemaDef);
    return {
      encode(val) {
        const w = new AvroWriter(256);
        writeVal(w, rootNode, val);
        return w.toUint8Array();
      },
      decode(u8, offset) {
        const r = new AvroReader(u8, offset || 0);
        return readVal(r, rootNode);
      },
      decodeWithConsumption(u8, offset) {
        const startOffset = offset || 0;
        const r = new AvroReader(u8, startOffset);
        const value = readVal(r, rootNode);
        return { value, offset: r.pos, consumed: r.pos - startOffset };
      },
    };
  }

  // ---- RLE Chunk Codec + Deterministic 32^3 Terrain ----
  function chunkIndex(x, y, z) {
    return (x << 10) + (y << 5) + z;
  }

  function rleEncode(values) {
    const out = [];
    let i = 0;
    const len = values.length;
    while (i < len) {
      const value = values[i] >>> 0;
      let count = 1;
      while (i + count < len && values[i + count] === value) count++;
      let n = count;
      while (n >= 128) { out.push(0x80 | (n & 0x7f)); n >>>= 7; }
      out.push(n);
      let v = value;
      while (v >= 128) { out.push(0x80 | (v & 0x7f)); v >>>= 7; }
      out.push(v);
      i += count;
    }
    return Uint8Array.from(out);
  }

  function rleDecode(bytes, outputLength) {
    const out = new Uint16Array(outputLength || 32768);
    let p = 0;
    let n = 0;
    while (p < bytes.length && n < out.length) {
      let count = 0;
      let shift = 0;
      let b = 0;
      do { b = bytes[p++]; count |= (b & 0x7f) << shift; shift += 7; } while (b >= 128);
      let value = 0;
      shift = 0;
      do { b = bytes[p++]; value |= (b & 0x7f) << shift; shift += 7; } while (b >= 128);
      const end = Math.min(out.length, n + count);
      out.fill(value, n, end);
      n = end;
    }
    return out;
  }

  function seedStringToUint32(seedStr) {
    const s = String(seedStr || 'fable_1');
    let h = 2166136261 >>> 0;
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 16777619) >>> 0;
    }
    return h >>> 0;
  }

  function hash2d(x, z, seedU32) {
    let h = (seedU32 ^ Math.imul(x | 0, 374761393) ^ Math.imul(z | 0, 668265263)) >>> 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177) >>> 0;
    return (h ^ (h >>> 16)) >>> 0;
  }

  function valueNoise2d(x, z, seedU32) {
    const xi = Math.floor(x);
    const zi = Math.floor(z);
    const xf = x - xi;
    const zf = z - zi;
    const u = xf * xf * (3 - 2 * xf);
    const v = zf * zf * (3 - 2 * zf);
    const n00 = (hash2d(xi, zi, seedU32) & 0xffff) / 65535;
    const n10 = (hash2d(xi + 1, zi, seedU32) & 0xffff) / 65535;
    const n01 = (hash2d(xi, zi + 1, seedU32) & 0xffff) / 65535;
    const n11 = (hash2d(xi + 1, zi + 1, seedU32) & 0xffff) / 65535;
    return (n00 + u * (n10 - n00)) + v * ((n01 + u * (n11 - n01)) - (n00 + u * (n10 - n00)));
  }

  function surfaceHeightAt(wx, wz, seedU32) {
    // Keep spawn zone [12..21, 12..21] smoothly around y = -4 so [16.5, -2, 16.5] stands cleanly above ground
    const dx = wx - 16;
    const dz = wz - 16;
    const distSq = dx * dx + dz * dz;
    if (distSq <= 25) return -4;
    const f1 = valueNoise2d(wx * 0.025, wz * 0.025, seedU32);
    const f2 = valueNoise2d(wx * 0.075, wz * 0.075, (seedU32 + 101) >>> 0);
    const raw = Math.floor(-8 + f1 * 9 + f2 * 3);
    if (distSq < 100) {
      const t = (distSq - 25) / 75;
      return Math.round(-4 * (1 - t) + raw * t);
    }
    return raw;
  }

  function baseBlockAt(wx, wy, wz, seedU32) {
    if (wy <= -32) return 147; // Bedrock
    const surf = surfaceHeightAt(wx, wz, seedU32);
    if (wy > surf) return 0; // Air
    if (wy === surf) return surf <= -7 ? 5 : 4; // Sand near low basins, Grass Block otherwise
    if (wy >= surf - 3) return 2; // Dirt
    const r = hash2d(wx + wy * 31, wz - wy * 17, (seedU32 + 911) >>> 0) & 0x3ff;
    if (wy < -24 && r < 4) return 45; // Diamond Ore
    if (wy < -20 && r >= 4 && r < 7) return 465; // Moonstone Ore
    if (wy < -18 && r >= 7 && r < 14) return 42; // Gold Ore
    if (r >= 14 && r < 30) return 41; // Iron Ore
    if (r >= 30 && r < 54) return 40; // Coal Ore
    return 28; // Stone
  }

  function generateChunk32(cx, cy, cz, seedU32, overridesMap) {
    const voxels = new Uint16Array(32768);
    const baseX = cx * 32;
    const baseY = cy * 32;
    const baseZ = cz * 32;

    if (baseY > 16) {
      // High sky fast path
      if (overridesMap && overridesMap.size > 0) {
        for (const [key, blockId] of overridesMap.entries()) {
          const parts = key.split(',');
          const wx = Number(parts[0]);
          const wy = Number(parts[1]);
          const wz = Number(parts[2]);
          if (wx >= baseX && wx < baseX + 32 && wy >= baseY && wy < baseY + 32 && wz >= baseZ && wz < baseZ + 32) {
            voxels[chunkIndex(wx - baseX, wy - baseY, wz - baseZ)] = blockId >>> 0;
          }
        }
      }
      return voxels;
    }

    for (let lx = 0; lx < 32; lx++) {
      const wx = baseX + lx;
      for (let lz = 0; lz < 32; lz++) {
        const wz = baseZ + lz;
        const surf = surfaceHeightAt(wx, wz, seedU32);
        for (let ly = 0; ly < 32; ly++) {
          const wy = baseY + ly;
          let block = 0;
          if (wy <= -32) block = 147;
          else if (wy > surf) block = 0;
          else if (wy === surf) block = surf <= -7 ? 5 : 4;
          else if (wy >= surf - 3) block = 2;
          else {
            const r = hash2d(wx + wy * 31, wz - wy * 17, (seedU32 + 911) >>> 0) & 0x3ff;
            if (wy < -24 && r < 4) block = 45;
            else if (wy < -20 && r >= 4 && r < 7) block = 465;
            else if (wy < -18 && r >= 7 && r < 14) block = 42;
            else if (r >= 14 && r < 30) block = 41;
            else if (r >= 30 && r < 54) block = 40;
            else block = 28;
          }
          voxels[chunkIndex(lx, ly, lz)] = block;
        }
      }
    }

    if (overridesMap && overridesMap.size > 0) {
      for (const [key, blockId] of overridesMap.entries()) {
        const parts = key.split(',');
        const wx = Number(parts[0]);
        const wy = Number(parts[1]);
        const wz = Number(parts[2]);
        if (wx >= baseX && wx < baseX + 32 && wy >= baseY && wy < baseY + 32 && wz >= baseZ && wz < baseZ + 32) {
          voxels[chunkIndex(wx - baseX, wy - baseY, wz - baseZ)] = blockId >>> 0;
        }
      }
    }
    return voxels;
  }

  // ---- Chunk 156 Colyseus Schema + MsgPack Runtime Loader ----
  let cachedRuntimePromise = null;

  function loadChunk156Runtime(chunk156UrlOrPath) {
    if (cachedRuntimePromise && !chunk156UrlOrPath) return cachedRuntimePromise;
    const p = (async () => {
      let target = chunk156UrlOrPath;
      if (!target) {
        if (typeof window !== 'undefined' && window.location) {
          target = '/static/js/7rp1w.156.69c43f4d.chunk.js';
        } else {
          const pathMod = require('node:path');
          target = 'file://' + pathMod.resolve(__dirname, 'static', 'js', '7rp1w.156.69c43f4d.chunk.js');
        }
      } else if (typeof window === 'undefined' && !String(target).startsWith('file://')) {
        const pathMod = require('node:path');
        target = 'file://' + pathMod.resolve(String(target));
      }
      const c156Mod = await import(target);
      const mods = { ...c156Mod.modules };
      const cache = {};
      function req(id) {
        if (cache[id]) return cache[id].exports;
        if (!mods[id]) throw new Error('Missing chunk 156 module ' + id);
        const m = (cache[id] = { exports: {} });
        mods[id](m, m.exports, req);
        return m.exports;
      }
      req.d = (exp, def) => {
        for (const k of Object.keys(def)) {
          if (!Object.prototype.hasOwnProperty.call(exp, k)) {
            Object.defineProperty(exp, k, { enumerable: true, get: def[k] });
          }
        }
      };
      req.r = (exp) => {
        Object.defineProperty(exp, '__esModule', { value: true });
      };
      req.n = (m) => {
        const g = m && m.__esModule ? () => m.default : () => m;
        req.d(g, { a: g });
        return g;
      };

      const schema = req(7015);
      const msgpack = req(7056);
      const coly = req(6966);

      class ArmourPiece extends schema.Schema {
        constructor() {
          super();
          this.itemName = '';
          this.enchantmentTier = '';
        }
      }
      schema.defineTypes(ArmourPiece, { itemName: 'string', enchantmentTier: 'string' });

      class DroppedItem extends schema.Schema {
        constructor() {
          super();
          this.id = 0;
          this.amount = 0;
          this.x = 0;
          this.y = 0;
          this.z = 0;
          this.size = 1;
          this.attributes = '{}';
        }
      }
      schema.defineTypes(DroppedItem, {
        id: 'number',
        amount: 'number',
        x: 'number',
        y: 'number',
        z: 'number',
        size: 'number',
        attributes: 'string',
      });

      class EntityState extends schema.Schema {
        constructor() {
          super();
          this.type = 'Player';
          this.name = 'Explorer';
          this.x = DEFAULT_SPAWN[0];
          this.y = DEFAULT_SPAWN[1];
          this.z = DEFAULT_SPAWN[2];
          this.xRotation = 0;
          this.yRotation = 0;
          this.zRotation = 0;
          this.followingEId = '';
          this.followingOffset = new schema.ArraySchema(0, 0, 0);
          this.shouldMatchFollowingEntityRotation = true;
          this.scale = 1;
          this.speed = 0;
          this.jumping = false;
          this.crouching = false;
          this.cameraPitch = 0;
          this.armSwinging = false;
          this.heldItemName = '';
          this.heldItemEnchantmentTier = '';
          this.pose = 'standing';
          this.ridingVehicleEId = '';
          this.meshOffset = new schema.ArraySchema(0, 0, 0);
          this.baseRenderHeading = 0;
          this.armour = new schema.MapSchema();
        }
      }
      schema.defineTypes(EntityState, {
        type: 'string',
        name: 'string',
        x: 'number',
        y: 'number',
        z: 'number',
        xRotation: 'float32',
        yRotation: 'float32',
        zRotation: 'float32',
        followingEId: 'string',
        followingOffset: ['number'],
        shouldMatchFollowingEntityRotation: 'boolean',
        scale: 'float32',
        speed: 'float32',
        jumping: 'boolean',
        crouching: 'boolean',
        cameraPitch: 'float32',
        armSwinging: 'boolean',
        heldItemName: 'string',
        heldItemEnchantmentTier: 'string',
        pose: 'string',
        ridingVehicleEId: 'string',
        meshOffset: ['number'],
        baseRenderHeading: 'number',
        armour: { map: ArmourPiece },
      });

      class RoomState extends schema.Schema {
        constructor() {
          super();
          this.entities = new schema.MapSchema();
          this.items = new schema.MapSchema();
          this.tickCounter = 0;
        }
      }
      schema.defineTypes(RoomState, {
        entities: { map: EntityState },
        items: { map: DroppedItem },
        tickCounter: 'number',
      });

      return { schema, msgpack, coly, ArmourPiece, DroppedItem, EntityState, RoomState };
    })();
    if (!chunk156UrlOrPath) cachedRuntimePromise = p;
    return p;
  }

  // ---- Colyseus + WASM Wire Framing ----
  function encodeColyNumber(rt, num) {
    const tmp = new Uint8Array(9);
    const it = { offset: 0 };
    rt.schema.encode.number(tmp, num, it);
    return tmp.subarray(0, it.offset);
  }

  function buildJoinRoomFrame(sessionId, serializerId, handshakeBytes) {
    const sBytes = textEncoder.encode(sessionId);
    const zBytes = textEncoder.encode(serializerId);
    const hs = handshakeBytes || new Uint8Array(0);
    const out = new Uint8Array(1 + 1 + sBytes.length + 1 + zBytes.length + hs.length);
    let o = 0;
    out[o++] = PROTOCOL.JOIN_ROOM;
    out[o++] = sBytes.length;
    out.set(sBytes, o); o += sBytes.length;
    out[o++] = zBytes.length;
    out.set(zBytes, o); o += zBytes.length;
    if (hs.length > 0) out.set(hs, o);
    return out;
  }

  function buildRoomStateFrame(stateBytes) {
    const out = new Uint8Array(1 + stateBytes.length);
    out[0] = PROTOCOL.ROOM_STATE;
    out.set(stateBytes, 1);
    return out;
  }

  function buildRoomStatePatchFrame(patchBytes) {
    const out = new Uint8Array(1 + patchBytes.length);
    out[0] = PROTOCOL.ROOM_STATE_PATCH;
    out.set(patchBytes, 1);
    return out;
  }

  function buildRoomDataFrame(rt, opcode, payload) {
    const hdr = encodeColyNumber(rt, opcode);
    const body = payload === undefined ? new Uint8Array(0) : Uint8Array.from(rt.msgpack.pack(payload));
    const out = new Uint8Array(1 + hdr.length + body.length);
    out[0] = PROTOCOL.ROOM_DATA;
    out.set(hdr, 1);
    if (body.length > 0) out.set(body, 1 + hdr.length);
    return out;
  }

  function buildRoomDataBytesFrame(rt, opcode, avroBytes) {
    const hdr = encodeColyNumber(rt, opcode);
    const out = new Uint8Array(1 + hdr.length + avroBytes.length);
    out[0] = PROTOCOL.ROOM_DATA_BYTES;
    out.set(hdr, 1);
    out.set(avroBytes, 1 + hdr.length);
    return out;
  }

  function cipherOpcode(opcode, adjustments, useJsMask42) {
    const u = Array.isArray(adjustments) || ArrayBuffer.isView(adjustments) ? adjustments : [];
    const mask42 = useJsMask42 ? CAPTURED_JS_XOR_MASK : 0;
    return opcode ^ (u[2] | 0) ^ u.length ^ mask42 ^ (u[6] | 0) ^ CIPHER_VERSION_PARAM;
  }

  function cipherPayloadBytes(bytes, adjustments, useJsMask42) {
    const src = bytes instanceof Uint8Array ? bytes : Uint8Array.from(bytes || []);
    const u = Array.isArray(adjustments) || ArrayBuffer.isView(adjustments) ? adjustments : [];
    const L = u.length;
    const mask42 = useJsMask42 ? CAPTURED_JS_XOR_MASK : 0;
    const len3 = src.length * 3;
    const out = new Uint8Array(src.length);
    for (let i = 0; i < src.length; i++) {
      const kp = L > 0 ? (u[i % L] | 0) : 0;
      const ip = L > 0 ? (u[(i + 5) % L] | 0) : 0;
      out[i] = (src[i] ^ kp ^ mask42 ^ (i + len3) ^ ip ^ CIPHER_VERSION_PARAM) & 0xff;
    }
    return out;
  }

  function encodeWasmClientBytesFrame(rt, opcode, avroBytes, adjustments) {
    const encOp = cipherOpcode(opcode, adjustments, false);
    const hdr = encodeColyNumber(rt, encOp);
    const encPayload = cipherPayloadBytes(avroBytes, adjustments, false);
    const out = new Uint8Array(1 + hdr.length + encPayload.length);
    out[0] = PROTOCOL.ROOM_DATA_BYTES;
    out.set(hdr, 1);
    out.set(encPayload, 1 + hdr.length);
    return out;
  }

  function encodeWasmClientDataFrame(rt, opcode, payload, adjustments) {
    const encOp = cipherOpcode(opcode, adjustments, false);
    const hdr = encodeColyNumber(rt, encOp);
    const body = payload === undefined ? new Uint8Array(0) : Uint8Array.from(rt.msgpack.pack(payload));
    const out = new Uint8Array(1 + hdr.length + body.length);
    out[0] = PROTOCOL.ROOM_DATA;
    out.set(hdr, 1);
    if (body.length > 0) out.set(body, 1 + hdr.length);
    return out;
  }

  function encodeCapturedJsClientBytesFrame(rt, opcode, avroBytes, adjustments) {
    const encOp = cipherOpcode(opcode, adjustments, true);
    const hdr = encodeColyNumber(rt, encOp);
    const encPayload = cipherPayloadBytes(avroBytes, adjustments, true);
    const out = new Uint8Array(1 + hdr.length + encPayload.length);
    out[0] = PROTOCOL.ROOM_DATA_BYTES;
    out.set(hdr, 1);
    out.set(encPayload, 1 + hdr.length);
    return out;
  }

  function encodeCapturedJsClientDataFrame(rt, opcode, payload, adjustments) {
    const encOp = cipherOpcode(opcode, adjustments, true);
    const hdr = encodeColyNumber(rt, encOp);
    const body = payload === undefined ? new Uint8Array(0) : Uint8Array.from(rt.msgpack.pack(payload));
    const out = new Uint8Array(1 + hdr.length + body.length);
    out[0] = PROTOCOL.ROOM_DATA;
    out.set(hdr, 1);
    if (body.length > 0) out.set(body, 1 + hdr.length);
    return out;
  }

  function decodeClientWireFrame(rt, c2sCodecs, rawBytes, decodeOpts) {
    try {
      const u8 = rawBytes instanceof Uint8Array
        ? rawBytes
        : (rawBytes instanceof ArrayBuffer ? new Uint8Array(rawBytes) : Uint8Array.from(rawBytes || []));
      if (u8.length === 0) return { kind: 'empty' };
      const proto = u8[0];
      if (proto === PROTOCOL.JOIN_ROOM && u8.length === 1) return { kind: 'join_ack' };
      if (proto === PROTOCOL.LEAVE_ROOM) return { kind: 'leave' };

      const opts = decodeOpts || {};
      const adj = opts.adjustments || [];
      const knownOpcodes = opts.knownOpcodes || null;
      const modeOrder = opts.detectedJsCipher === false
        ? [false, true]
        : [true, false];

      if (proto === PROTOCOL.ROOM_DATA) {
        if (u8.length < 2) return { kind: 'malformed', error: 'Truncated ROOM_DATA header' };
        const it = { offset: 1 };
        const rawOp = rt.schema.decode.stringCheck(u8, it)
          ? rt.schema.decode.string(u8, it)
          : rt.schema.decode.number(u8, it);
        if (it.offset > u8.length) return { kind: 'malformed', error: 'ROOM_DATA opcode overread' };

        const bodySlice = u8.subarray(it.offset);
        let opcode = rawOp;
        let cipherMode = 'plain';
        let unambiguous = false;

        if (typeof rawOp === 'number' && rawOp >= 1024) {
          let best = null;
          let validModes = 0;
          for (const useJs of modeOrder) {
            const candOp = cipherOpcode(rawOp, adj, useJs);
            if (candOp < 0 || candOp >= 1024) continue;
            const hasAvroCodec = c2sCodecs.has(String(candOp));
            const isKnown = knownOpcodes ? knownOpcodes.has(candOp) : true;
            if (!isKnown && !hasAvroCodec) continue;
            validModes += 1;
            let score = 0;
            if (isKnown) score += 20;
            // Empty ROOM_DATA frames belong to schemaless opcodes like Ki=15 respawn, not Avro-schema opcodes
            if (bodySlice.length === 0 && !hasAvroCodec) score += 40;
            if (bodySlice.length === 0 && hasAvroCodec) score -= 15;
            if (opts.detectedJsCipher === useJs) score += 10;
            if (!best || score > best.score) {
              best = { opcode: candOp, useJs, score };
            }
          }
          if (!best) return { kind: 'malformed', error: 'Unrecognized ciphered ROOM_DATA opcode: ' + rawOp };
          opcode = best.opcode;
          cipherMode = best.useJs ? 'js9445' : 'wasm9423';
          unambiguous = validModes === 1;
        }

        let data = undefined;
        if (bodySlice.length > 0) {
          data = rt.msgpack.unpack(bodySlice);
        }
        return { kind: 'message', opcode, data, cipherMode, unambiguous };
      }

      if (proto === PROTOCOL.ROOM_DATA_BYTES) {
        if (u8.length < 2) return { kind: 'malformed', error: 'Truncated ROOM_DATA_BYTES header' };
        const it = { offset: 1 };
        const rawOp = rt.schema.decode.stringCheck(u8, it)
          ? Number(rt.schema.decode.string(u8, it))
          : rt.schema.decode.number(u8, it);
        if (!Number.isFinite(rawOp) || it.offset > u8.length) {
          return { kind: 'malformed', error: 'Invalid ROOM_DATA_BYTES opcode header' };
        }
        const ciphered = typeof rawOp === 'number' && rawOp >= 1024;
        const rawPayload = u8.subarray(it.offset);

        if (!ciphered) {
          const codec = c2sCodecs.get(String(rawOp));
          if (codec) {
            const res = codec.decodeWithConsumption(rawPayload, 0);
            if (res.consumed !== rawPayload.length) {
              return { kind: 'malformed', error: 'Trailing bytes in plain ROOM_DATA_BYTES payload' };
            }
            return { kind: 'message', opcode: rawOp, data: res.value, rawBytes: rawPayload, cipherMode: 'plain', unambiguous: false };
          }
          return { kind: 'message', opcode: rawOp, data: rawPayload, rawBytes: rawPayload, cipherMode: 'plain', unambiguous: false };
        }

        let best = null;
        let validSchemaMatches = 0;
        for (const useJs of modeOrder) {
          const candOp = cipherOpcode(rawOp, adj, useJs);
          if (candOp < 0 || candOp >= 1024) continue;
          const codec = c2sCodecs.get(String(candOp));
          const isKnown = knownOpcodes ? knownOpcodes.has(candOp) : Boolean(codec);
          const candPlain = cipherPayloadBytes(rawPayload, adj, useJs);

          if (codec) {
            try {
              const res = codec.decodeWithConsumption(candPlain, 0);
              if (res.consumed !== candPlain.length) continue;
              validSchemaMatches += 1;
              let score = 100 + (isKnown ? 10 : 0);
              if (opts.detectedJsCipher === useJs) score += 25;
              if (!best || score > best.score) {
                best = { opcode: candOp, data: res.value, rawBytes: candPlain, useJs, score };
              }
            } catch (_) {
              // Schema/bounds mismatch under this cipher candidate; reject candidate
            }
          } else if (isKnown && candPlain.length === 0) {
            validSchemaMatches += 1;
            const score = 30 + (opts.detectedJsCipher === useJs ? 25 : 0);
            if (!best || score > best.score) {
              best = { opcode: candOp, data: candPlain, rawBytes: candPlain, useJs, score };
            }
          }
        }

        if (!best) {
          return { kind: 'malformed', error: 'No valid C2S cipher/schema match for rawOp=' + rawOp };
        }
        return {
          kind: 'message',
          opcode: best.opcode,
          data: best.data,
          rawBytes: best.rawBytes,
          cipherMode: best.useJs ? 'js9445' : 'wasm9423',
          unambiguous: validSchemaMatches === 1,
        };
      }
      return { kind: 'unknown', proto };
    } catch (err) {
      return { kind: 'malformed', error: err && err.message ? err.message : 'Wire frame decode error' };
    }
  }

  // ---- Code Document & Collaboration Helpers ----
  const COLLAB_OP = {
    Join: 0,
    Leave: 1,
    Push: 2,
    Cursor: 3,
    Submit: 4,
    ReloadAsVariation: 5,
    CreateFile: 6,
    RenameFile: 7,
    DeleteFile: 8,
    SetEngine: 9,
    RenameFolder: 10,
  };

  const COLLAB_EVT = {
    Joined: 0,
    Updates: 1,
    Presence: 2,
    RemoteCursor: 3,
    ForceClose: 4,
    LobbyFilesChanged: 5,
    EngineSettings: 6,
  };

  function hasAllowedCodeExtension(filePath) {
    if (filePath.endsWith('.d.ts')) return true;
    if (filePath.endsWith('.ts')) return true;
    if (filePath.endsWith('.js') && !filePath.endsWith('.min.js') && !filePath.endsWith('.bundle.js')) {
      return true;
    }
    return false;
  }

  // Exact match to captured chunk 82 path validator TF(FF)
  function base64ToUint8Array(b64) {
    if (!b64 || typeof b64 !== 'string') return new Uint8Array(0);
    if (typeof Buffer !== 'undefined' && typeof Buffer.from === 'function') {
      const buf = Buffer.from(b64, 'base64');
      return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    }
    if (typeof atob === 'function') {
      const bin = atob(b64);
      const out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    }
    return new Uint8Array(0);
  }

  function safeDecodeUriSegment(raw) {
    if (typeof raw !== 'string' || !raw.includes('%')) return raw;
    try {
      return decodeURIComponent(raw);
    } catch (_) {
      return raw;
    }
  }

  function buildChunk32Rle(cx, cy, cz, seedU32, overridesMap, nativeBridge) {
    const baseX = cx * 32;
    const baseY = cy * 32;
    const baseZ = cz * 32;
    let chunkOverrides = null;
    if (overridesMap && overridesMap.size > 0) {
      for (const [key, blockId] of overridesMap.entries()) {
        const parts = key.split(',');
        const wx = Number(parts[0]);
        const wy = Number(parts[1]);
        const wz = Number(parts[2]);
        if (wx >= baseX && wx < baseX + 32 && wy >= baseY && wy < baseY + 32 && wz >= baseZ && wz < baseZ + 32) {
          if (!chunkOverrides) chunkOverrides = [];
          chunkOverrides.push([wx - baseX, wy - baseY, wz - baseZ, blockId >>> 0]);
        }
      }
    }
    if (nativeBridge && typeof nativeBridge.generateChunk32RleBase64 === 'function') {
      try {
        const b64 = nativeBridge.generateChunk32RleBase64(cx | 0, cy | 0, cz | 0, seedU32 | 0, 0);
        if (b64 && typeof b64 === 'string' && b64.length > 0) {
          const nativeRle = base64ToUint8Array(b64);
          if (nativeRle.length > 0) {
            if (!chunkOverrides) return nativeRle;
            const voxels = rleDecode(nativeRle, 32768);
            for (let i = 0; i < chunkOverrides.length; i++) {
              const o = chunkOverrides[i];
              voxels[chunkIndex(o[0], o[1], o[2])] = o[3];
            }
            return rleEncode(voxels);
          }
        }
      } catch (_) {}
    }
    const voxels = generateChunk32(cx, cy, cz, seedU32, overridesMap);
    return rleEncode(voxels);
  }

  function isValidCapturedLobbyPath(rawPath) {
    if (typeof rawPath !== 'string' || !rawPath || rawPath !== rawPath.trim()) return false;
    if (rawPath.length > 64) return false;
    if (rawPath.startsWith('/') || rawPath.includes('\\') || rawPath.includes('\0')) return false;
    if (!hasAllowedCodeExtension(rawPath)) return false;
    const segments = rawPath.split('/');
    for (const seg of segments) {
      if (seg === '' || seg === '.' || seg === '..') return false;
    }
    return true;
  }

  function normalizeLobbyFolderPath(rawFolder) {
    if (typeof rawFolder !== 'string' || rawFolder.includes('\0')) throw new Error('Invalid folder path');
    const clean = rawFolder.replace(/\\/g, '/').trim().replace(/^\/+|\/+$/g, '');
    if (!clean || clean.length > 64) throw new Error('Unsafe or empty folder path');
    const segments = clean.split('/');
    for (const seg of segments) {
      if (!seg || seg === '.' || seg === '..') throw new Error('Path traversal denied');
    }
    return segments.join('/');
  }

  function normalizeLobbyPath(rawPath) {
    if (typeof rawPath !== 'string' || rawPath.includes('\0')) throw new Error('Invalid code path');
    const clean = rawPath.replace(/\\/g, '/').trim().replace(/^\/+/, '');
    if (!clean || clean.length > 64) throw new Error('Unsafe or empty code path');
    const segments = clean.split('/');
    for (const seg of segments) {
      if (!seg || seg === '.' || seg === '..') throw new Error('Path traversal denied');
    }
    const joined = segments.join('/');
    if (!hasAllowedCodeExtension(joined)) {
      throw new Error('Unsupported code file extension');
    }
    return joined;
  }

  // CodeMirror 6 ChangeSet.toJSON() incremental change applier (UTF-16 code unit offsets)
  function applyCodeMirrorChangeSet(docText, changesJson) {
    if (typeof docText !== 'string') throw new Error('Document text must be a string');
    if (!Array.isArray(changesJson)) throw new Error('CodeMirror ChangeSet JSON must be an array');
    let pos = 0;
    let out = '';
    for (let i = 0; i < changesJson.length; i++) {
      const item = changesJson[i];
      if (typeof item === 'number') {
        if (!Number.isInteger(item) || item < 0 || pos + item > docText.length) {
          throw new RangeError('CodeMirror retain count out of bounds');
        }
        out += docText.slice(pos, pos + item);
        pos += item;
      } else if (Array.isArray(item) && item.length >= 1) {
        const delLen = item[0];
        if (!Number.isInteger(delLen) || delLen < 0 || pos + delLen > docText.length) {
          throw new RangeError('CodeMirror delete length out of bounds');
        }
        pos += delLen;
        if (item.length > 1) {
          const lines = [];
          for (let j = 1; j < item.length; j++) {
            if (typeof item[j] !== 'string') throw new Error('CodeMirror inserted line must be string');
            lines.push(item[j]);
          }
          out += lines.join('\n');
        }
      } else {
        throw new Error('Malformed CodeMirror ChangeSet entry');
      }
    }
    if (pos !== docText.length) {
      throw new RangeError(`CodeMirror ChangeSet length mismatch: consumed ${pos} of ${docText.length}`);
    }
    return out;
  }

  function tryParseCodeMirrorUpdates(rawUpdates) {
    let parsed = rawUpdates;
    if (typeof rawUpdates === 'string') {
      const trimmed = rawUpdates.trim();
      if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) return null;
      try {
        parsed = JSON.parse(trimmed);
      } catch (_) {
        return null;
      }
    }
    if (!Array.isArray(parsed) || parsed.length === 0) return null;
    for (let i = 0; i < parsed.length; i++) {
      const u = parsed[i];
      if (!u || typeof u !== 'object' || Array.isArray(u) || !Array.isArray(u.changes)) {
        return null;
      }
    }
    return parsed;
  }

  function applyCodeMirrorUpdates(docText, updatesList, fallbackClientId) {
    let cur = docText;
    const normalized = [];
    for (let i = 0; i < updatesList.length; i++) {
      const u = updatesList[i];
      cur = applyCodeMirrorChangeSet(cur, u.changes);
      normalized.push({
        clientID: String(u.clientID || fallbackClientId || 'local_client'),
        changes: u.changes,
      });
    }
    return {
      code: cur,
      count: normalized.length,
      normalizedJson: JSON.stringify(normalized),
    };
  }

  function makeLobbyDocKey(rawPath) {
    return 'lobby:' + normalizeLobbyPath(rawPath);
  }

  function makeCodeBlockDocKey(x, y, z) {
    const ix = Number(x);
    const iy = Number(y);
    const iz = Number(z);
    if (!Number.isInteger(ix) || !Number.isInteger(iy) || !Number.isInteger(iz)) {
      throw new Error('Invalid code block coordinates');
    }
    return `codeBlock:${ix},${iy},${iz}`;
  }

  function validateDocKey(docKey) {
    if (typeof docKey !== 'string' || docKey.length === 0 || docKey.length > 80) {
      throw new Error('Malformed docKey');
    }
    if (docKey.startsWith('lobby:')) {
      return makeLobbyDocKey(docKey.slice('lobby:'.length));
    }
    if (docKey.startsWith('codeBlock:')) {
      const parts = docKey.slice('codeBlock:'.length).split(',');
      if (parts.length !== 3) throw new Error('Malformed codeBlock docKey');
      return makeCodeBlockDocKey(Number(parts[0]), Number(parts[1]), Number(parts[2]));
    }
    throw new Error('Unsupported docKey prefix');
  }

  // ---- Local Persistence Store (IndexedDB + Memory Fallback + Native Bridge Sync) ----
  function createPersistenceStore(initialBackingMap) {
    const mem = initialBackingMap instanceof Map ? initialBackingMap : new Map();

    async function idbOp(mode, fn) {
      if (typeof indexedDB === 'undefined') return undefined;
      return new Promise((resolve) => {
        try {
          const req = indexedDB.open('fable_local_bloxd_worlds', 1);
          req.onupgradeneeded = () => {
            const db = req.result;
            if (!db.objectStoreNames.contains('records')) db.createObjectStore('records');
          };
          req.onerror = () => resolve(undefined);
          req.onsuccess = () => {
            try {
              const db = req.result;
              const tx = db.transaction('records', mode);
              const st = tx.objectStore('records');
              let out;
              const r = fn(st);
              if (r && 'onsuccess' in r) r.onsuccess = () => { out = r.result; };
              tx.oncomplete = () => { db.close(); resolve(out); };
              tx.onerror = () => { db.close(); resolve(undefined); };
            } catch (_) {
              resolve(undefined);
            }
          };
        } catch (_) {
          resolve(undefined);
        }
      });
    }

    return {
      mem,
      async get(key) {
        if (mem.has(key)) return JSON.parse(JSON.stringify(mem.get(key)));
        const fromIdb = await idbOp('readonly', (st) => st.get(key));
        if (fromIdb !== undefined) {
          mem.set(key, fromIdb);
          return JSON.parse(JSON.stringify(fromIdb));
        }
        return null;
      },
      async set(key, value) {
        const snap = JSON.parse(JSON.stringify(value));
        mem.set(key, snap);
        await idbOp('readwrite', (st) => st.put(snap, key));
        return true;
      },
    };
  }

  // ---- Main Local Bloxd Engine (Rooms, Survival Gameplay, Code Collab, In-Process WebSocket) ----
  async function createLocalBloxdEngine(options) {
    const opts = options || {};
    const rt = await loadChunk156Runtime(opts.chunk156Path);
    const protocolMap = opts.protocolMap;
    const worldMeta = opts.worldMeta;
    const persistence = opts.persistence || createPersistenceStore(opts.backingMap);
    const nativeBridge = opts.nativeBridge || (typeof window !== 'undefined' ? window.FableLocalNative : null);

    if (!protocolMap || !worldMeta) {
      throw new Error('createLocalBloxdEngine requires protocolMap and worldMeta');
    }

    const op = protocolMap.opcodes || {};
    const s2cCodecs = new Map();
    const c2sCodecs = new Map();
    for (const [k, def] of Object.entries(protocolMap.s2cSchemas || {})) {
      if (def) s2cCodecs.set(String(k), createAvroSchemaCodec(def));
    }
    for (const [k, def] of Object.entries(protocolMap.c2sSchemas || {})) {
      if (def) c2sCodecs.set(String(k), createAvroSchemaCodec(def));
    }
    const knownC2SOpcodes = new Set();
    for (const v of Object.values(op)) {
      if (typeof v === 'number' && Number.isInteger(v)) knownC2SOpcodes.add(v);
    }
    for (const k of c2sCodecs.keys()) {
      const n = Number(k);
      if (Number.isInteger(n)) knownC2SOpcodes.add(n);
    }
    const defaultCipherAdjustments = Array.isArray(opts.initialAdjustments)
      ? opts.initialAdjustments.map((n) => Number(n) & 0xff)
      : [];

    // Build item metadata lookup maps from bloxd_world_meta.json
    const itemById = new Map();
    const itemByName = new Map();
    for (const [idStr, tuple] of Object.entries(worldMeta.items || {})) {
      if (!Array.isArray(tuple) || !tuple[0]) continue;
      const id = Number(idStr);
      const info = {
        id,
        name: String(tuple[0]),
        maxStack: Math.max(1, Math.min(999, Number(tuple[1]) || 999)),
        dropItemId: Number(tuple[2] != null ? tuple[2] : id),
        typeFlag: Number(tuple[3] || 0),
      };
      itemById.set(id, info);
      if (!itemByName.has(info.name)) itemByName.set(info.name, info);
    }
    const recipesByOutput = worldMeta.recipes || {};

    let bridgeInitialName = '';
    if (nativeBridge && typeof nativeBridge.getPlayerName === 'function') {
      try { bridgeInitialName = nativeBridge.getPlayerName() || ''; } catch (_) {}
    }
    let storageInitialName = '';
    if (typeof localStorage !== 'undefined') {
      try {
        storageInitialName =
          localStorage.getItem('fable_local_player_name') ||
          localStorage.getItem('fable_player_name') ||
          '';
      } catch (_) {}
    }
    let persistedPlayerName = normalizeUsername(
      opts.initialPlayerName || bridgeInitialName || storageInitialName,
      'Explorer'
    );

    const worlds = new Map();
    let nextEntityNum = 1;
    let nextDropSeq = 1;
    let nextChatId = 1;
    let nextTeleportId = 1;

    function resolveItemInfo(keyOrId) {
      if (typeof keyOrId === 'number' || /^\d+$/.test(String(keyOrId))) {
        return itemById.get(Number(keyOrId)) || null;
      }
      const q = String(keyOrId).trim();
      if (itemByName.has(q)) return itemByName.get(q);
      const lower = q.toLowerCase();
      for (const [name, info] of itemByName.entries()) {
        if (name.toLowerCase() === lower) return info;
      }
      return null;
    }

    function createStarterInventory() {
      const slots = new Array(INVENTORY_SLOTS).fill(null);
      slots[0] = { name: 'Wood Pickaxe', amount: 1, attributes: { customAttributes: {} } };
      slots[1] = { name: 'Dirt', amount: 32, attributes: { customAttributes: {} } };
      slots[2] = { name: 'Maple Log', amount: 8, attributes: { customAttributes: {} } };
      slots[3] = { name: 'Workbench', amount: 1, attributes: { customAttributes: {} } };
      slots[4] = { name: 'Torch', amount: 16, attributes: { customAttributes: {} } };
      return slots;
    }

    async function getOrCreateWorld(worldIdRaw, modeRaw) {
      const worldId = String(worldIdRaw || 'local_world').trim() || 'local_world';
      if (worlds.has(worldId)) return worlds.get(worldId);

      const saved = await persistence.get(`world:${worldId}`);
      const seed = (saved && saved.seed) || worldMeta.defaultSeed || 'fable_1';
      const seedU32 = seedStringToUint32(seed);
      const overrides = new Map();
      if (saved && saved.blockOverrides) {
        for (const [k, v] of Object.entries(saved.blockOverrides)) overrides.set(k, Number(v));
      }
      let nativeBundle = null;
      if (nativeBridge && typeof nativeBridge.loadWorldBundleJson === 'function') {
        try {
          nativeBundle = JSON.parse(nativeBridge.loadWorldBundleJson(worldId) || '{}');
          if (Array.isArray(nativeBundle.blocks)) {
            for (const row of nativeBundle.blocks) {
              if (Array.isArray(row) && row.length >= 4) {
                overrides.set(`${row[0]},${row[1]},${row[2]}`, Number(row[3]));
              }
            }
          }
        } catch (_) {}
      }

      const codeDocs = new Map();
      if (saved && saved.codeDocs) {
        for (const [k, d] of Object.entries(saved.codeDocs)) {
          codeDocs.set(k, {
            code: String(d.code || ''),
            revision: Number(d.revision || 0),
            canSeeCode: d.canSeeCode !== false,
            canEditCode: d.canEditCode !== false,
            engine: String(d.engine || 'default'),
          });
        }
      } else if (nativeBundle && nativeBundle.codeDocs && typeof nativeBundle.codeDocs === 'object') {
        for (const [k, d] of Object.entries(nativeBundle.codeDocs)) {
          if (d && typeof d === 'object') {
            codeDocs.set(k, {
              code: String(d.code || ''),
              revision: Number(d.revision || 0),
              canSeeCode: d.canSeeCode !== false,
              canEditCode: d.canEditCode !== false,
              engine: String(d.engine || 'default'),
            });
          }
        }
      }
      if (!codeDocs.has('lobby:main.js')) {
        codeDocs.set('lobby:main.js', {
          code: '// Local lobby script\n',
          revision: 0,
          canSeeCode: true,
          canEditCode: true,
          engine: 'default',
        });
      }

      const state = new rt.RoomState();
      const encoder = new rt.schema.Encoder(state);

      const world = {
        worldId,
        seed,
        seedU32,
        gameMode: (saved && saved.gameMode) || (String(modeRaw || '').includes('creative') ? 'creative' : 'survival'),
        worldTime: (saved && typeof saved.worldTime === 'number') ? saved.worldTime : 6000,
        overrides,
        codeDocs,
        codeEngine: (saved && saved.codeEngine) || 'default',
        codePeers: new Map(), // docKey -> Set<session>
        state,
        encoder,
        sessions: new Map(),
        savedPlayer: (saved && saved.player) || null,
        dropsData: new Map(),
        chatLog: [],
      };

      if (saved && Array.isArray(saved.drops)) {
        for (const d of saved.drops) {
          const dropKey = String(d.dropKey || `drop_${nextDropSeq++}`);
          const seqMatch = /^drop_(\d+)$/.exec(dropKey);
          if (seqMatch) {
            const seqNum = Number(seqMatch[1]);
            if (Number.isInteger(seqNum) && seqNum >= nextDropSeq) nextDropSeq = seqNum + 1;
          }
          const di = new rt.DroppedItem();
          di.id = Number(d.id) || 2;
          di.amount = Math.max(1, Number(d.amount) || 1);
          di.x = Number(d.x) || DEFAULT_SPAWN[0];
          di.y = Number(d.y) || DEFAULT_SPAWN[1];
          di.z = Number(d.z) || DEFAULT_SPAWN[2];
          di.size = 1;
          di.attributes = d.attributes || '{}';
          state.items.set(dropKey, di);
          world.dropsData.set(dropKey, {
            dropKey,
            id: di.id,
            amount: di.amount,
            x: di.x,
            y: di.y,
            z: di.z,
            attributes: di.attributes,
            createdAt: d.createdAt || Date.now(),
          });
        }
      }

      worlds.set(worldId, world);
      return world;
    }

    async function persistWorld(world, activeSession) {
      if (activeSession) {
        world.savedPlayer = {
          name: activeSession.playerName,
          x: activeSession.x,
          y: activeSession.y,
          z: activeSession.z,
          xRotation: activeSession.xRotation,
          yRotation: activeSession.yRotation,
          health: activeSession.health,
          isAlive: activeSession.isAlive,
          selectedSlot: activeSession.selectedSlot,
          inventory: activeSession.inventory,
        };
      }
      const blockOverrides = {};
      for (const [k, v] of world.overrides.entries()) blockOverrides[k] = v;
      const codeDocs = {};
      for (const [k, d] of world.codeDocs.entries()) codeDocs[k] = { ...d };
      const drops = Array.from(world.dropsData.values());
      const snapshot = {
        worldId: world.worldId,
        seed: world.seed,
        gameMode: world.gameMode,
        worldTime: world.worldTime,
        blockOverrides,
        codeDocs,
        codeEngine: world.codeEngine,
        player: world.savedPlayer,
        drops,
      };
      await persistence.set(`world:${world.worldId}`, snapshot);
      if (nativeBridge && typeof nativeBridge.saveWorldMetaJson === 'function') {
        try { nativeBridge.saveWorldMetaJson(world.worldId, JSON.stringify({ seed: world.seed, gameMode: world.gameMode, player: world.savedPlayer })); } catch (_) {}
      }
      return snapshot;
    }

    function getWorldBlock(world, wx, wy, wz) {
      const ix = Math.floor(wx);
      const iy = Math.floor(wy);
      const iz = Math.floor(wz);
      const key = `${ix},${iy},${iz}`;
      if (world.overrides.has(key)) return world.overrides.get(key);
      return baseBlockAt(ix, iy, iz, world.seedU32);
    }

    function isSolidBlock(blockId) {
      if (!blockId || blockId <= 0) return false;
      // Torches (2907) and water (0) are non-solid
      if (blockId === 2907) return false;
      return true;
    }

    function setWorldBlock(world, wx, wy, wz, blockId) {
      const ix = Math.floor(wx);
      const iy = Math.floor(wy);
      const iz = Math.floor(wz);
      const key = `${ix},${iy},${iz}`;
      world.overrides.set(key, blockId >>> 0);
      if (nativeBridge && typeof nativeBridge.setWorldBlockEdit === 'function') {
        try { nativeBridge.setWorldBlockEdit(world.worldId, ix, iy, iz, blockId >>> 0); } catch (_) {}
      }
    }

    function sendS2C(session, opcode, payload) {
      const codec = s2cCodecs.get(String(opcode));
      if (codec) {
        const avroBytes = codec.encode(payload);
        session.deliverFrame(buildRoomDataBytesFrame(rt, opcode, avroBytes));
      } else {
        session.deliverFrame(buildRoomDataFrame(rt, opcode, payload));
      }
    }

    function broadcastS2C(world, opcode, payload) {
      for (const sess of world.sessions.values()) {
        if (sess.joined) sendS2C(sess, opcode, payload);
      }
    }

    function broadcastPatch(world) {
      world.state.tickCounter = (world.state.tickCounter + 1) | 0;
      const patchBytes = world.encoder.encode();
      if (!patchBytes || patchBytes.length === 0) return;
      const frame = buildRoomStatePatchFrame(patchBytes);
      for (const sess of world.sessions.values()) {
        if (sess.joined) sess.deliverFrame(frame);
      }
    }

    function buildClientOptions(gameMode) {
      const isCreative = gameMode === 'creative';
      return {
        canChange: true,
        canCraft: true,
        canEditCode: true,
        canPickBlocks: true,
        canPickUpItems: true,
        creative: isCreative,
        canUseDevCreativeItems: isCreative,
        canAltAction: true,
        canCrouch: true,
        useInventory: true,
        useFullInventory: true,
        defaultBlock: 2,
        canSeeNametagsThroughWalls: true,
        walkingSpeed: 4.5,
        runningSpeedMultiplier: 1.45,
        crouchingSpeedMultiplier: 0.55,
        jumpAmount: 8.2,
      };
    }

    function syncHeldItemOnEntity(session) {
      const slotItem = session.inventory[session.selectedSlot] || null;
      session.entity.heldItemName = slotItem ? slotItem.name : '';
    }

    function syncInventoryS2C(session) {
      syncHeldItemOnEntity(session);
      sendS2C(session, op.se || 91, {
        L_: {
          N_: JSON.stringify(session.inventory),
          U_: null,
          Y_: null,
          S_: null,
          l_: null,
        },
        P_: false,
      });
      sendS2C(session, op.de || 105, session.selectedSlot);
    }

    function addItemToInventory(slots, itemName, amount, attributes) {
      const info = resolveItemInfo(itemName);
      if (!info || amount <= 0) return 0;
      let remaining = Math.floor(amount);
      const maxStack = info.maxStack;
      const attrs = attributes ? JSON.parse(JSON.stringify(attributes)) : { customAttributes: {} };
      if (!attrs.customAttributes) attrs.customAttributes = {};

      if (maxStack > 1) {
        for (let i = 0; i < slots.length && remaining > 0; i++) {
          const cur = slots[i];
          if (cur && cur.name === info.name && cur.amount < maxStack) {
            const add = Math.min(remaining, maxStack - cur.amount);
            cur.amount += add;
            remaining -= add;
          }
        }
      }
      for (let i = 0; i < slots.length && remaining > 0; i++) {
        if (!slots[i]) {
          const add = Math.min(remaining, maxStack);
          slots[i] = { name: info.name, amount: add, attributes: JSON.parse(JSON.stringify(attrs)) };
          remaining -= add;
        }
      }
      return Math.floor(amount) - remaining;
    }

    function countMatchingIngredient(slots, allowedNames) {
      const set = new Set(allowedNames);
      let total = 0;
      for (let i = 0; i < slots.length; i++) {
        if (slots[i] && set.has(slots[i].name)) total += slots[i].amount;
      }
      return total;
    }

    function consumeMatchingIngredient(slots, allowedNames, needed) {
      const set = new Set(allowedNames);
      let rem = needed;
      for (let i = 0; i < slots.length && rem > 0; i++) {
        const s = slots[i];
        if (s && set.has(s.name)) {
          const take = Math.min(rem, s.amount);
          s.amount -= take;
          rem -= take;
          if (s.amount <= 0) slots[i] = null;
        }
      }
      return rem === 0;
    }

    function spawnWorldDrop(world, itemId, amount, x, y, z, attributesObj) {
      const info = itemById.get(Number(itemId));
      if (!info || amount <= 0) return null;
      const dropKey = `drop_${nextDropSeq++}`;
      const attrStr = JSON.stringify(attributesObj || { customAttributes: {} });
      const di = new rt.DroppedItem();
      di.id = info.id;
      di.amount = Math.floor(amount);
      di.x = x;
      di.y = y;
      di.z = z;
      di.size = 1;
      di.attributes = attrStr;
      world.state.items.set(dropKey, di);
      const meta = {
        dropKey,
        id: info.id,
        amount: di.amount,
        x,
        y,
        z,
        attributes: attrStr,
        createdAt: Date.now(),
      };
      world.dropsData.set(dropKey, meta);
      broadcastS2C(world, op.Xb || 32, {
        U1: dropKey,
        Y1: di.id,
        E1: di.amount,
        yFF: attrStr,
        kFF: x,
        iFF: y,
        cFF: z,
        jFF: false,
      });
      return meta;
    }

    function collectNearbyDrops(world, session, radius) {
      if (!session.isAlive) return 0;
      const rad = radius || 2.1;
      const rSq = rad * rad;
      let pickedTotal = 0;
      const now = Date.now();
      for (const [dropKey, d] of Array.from(world.dropsData.entries())) {
        if (now - d.createdAt > 300000) {
          world.dropsData.delete(dropKey);
          world.state.items.delete(dropKey);
          broadcastS2C(world, op.Ci || 65, dropKey);
          continue;
        }
        const dx = d.x - session.x;
        const dy = d.y - session.y;
        const dz = d.z - session.z;
        if (dx * dx + dy * dy + dz * dz <= rSq) {
          const info = itemById.get(d.id);
          if (!info) continue;
          let attrs = {};
          try { attrs = JSON.parse(d.attributes || '{}'); } catch (_) {}
          const added = addItemToInventory(session.inventory, info.name, d.amount, attrs);
          if (added > 0) {
            pickedTotal += added;
            if (added >= d.amount) {
              world.dropsData.delete(dropKey);
              world.state.items.delete(dropKey);
              broadcastS2C(world, op.Ci || 65, dropKey);
            } else {
              d.amount -= added;
              const itemSchema = world.state.items.get(dropKey);
              if (itemSchema) itemSchema.amount = d.amount;
            }
          }
        }
      }
      if (pickedTotal > 0) {
        syncInventoryS2C(session);
        broadcastPatch(world);
      }
      return pickedTotal;
    }

    function playerOverlapsBlock(px, py, pz, bx, by, bz) {
      const halfW = 0.29;
      const height = 1.75;
      const minX = px - halfW;
      const maxX = px + halfW;
      const minY = py;
      const maxY = py + height;
      const minZ = pz - halfW;
      const maxZ = pz + halfW;
      return maxX > bx && minX < bx + 1 && maxY > by && minY < by + 1 && maxZ > bz && minZ < bz + 1;
    }

    function hasAdjacentSolidOrOverride(world, bx, by, bz) {
      const dirs = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
      for (const [dx, dy, dz] of dirs) {
        if (getWorldBlock(world, bx + dx, by + dy, bz + dz) > 0) return true;
      }
      return false;
    }

    // ---- Code Collaboration & Local HTTP Equivalents ----
    function ensureDocEntry(world, rawDocKey, createIfMissing) {
      const docKey = validateDocKey(rawDocKey);
      if (!world.codeDocs.has(docKey)) {
        if (!createIfMissing) return { docKey, doc: null };
        const defaultCode = docKey.startsWith('codeBlock:') ? '// Code Block\n' : '';
        world.codeDocs.set(docKey, {
          code: defaultCode,
          revision: 0,
          canSeeCode: true,
          canEditCode: true,
          engine: world.codeEngine || 'default',
        });
      }
      return { docKey, doc: world.codeDocs.get(docKey) };
    }

    function syncNativeCodeDoc(world, docKey, doc) {
      if (!nativeBridge || !doc) return;
      const safeRev = Math.max(0, Math.min(0x7fffffff, Math.trunc(Number(doc.revision) || 0)));
      const safeCode = typeof doc.code === 'string' ? doc.code : String(doc.code || '');
      if (typeof nativeBridge.syncCodeDocFull === 'function') {
        try {
          nativeBridge.syncCodeDocFull(
            String(world.worldId || 'local_world'),
            String(docKey || ''),
            safeCode,
            safeRev,
            doc.canSeeCode !== false,
            doc.canEditCode !== false,
            String(doc.engine || 'default')
          );
          return;
        } catch (_) {}
      }
      if (typeof nativeBridge.syncCodeDoc === 'function') {
        try { nativeBridge.syncCodeDoc(String(world.worldId || 'local_world'), String(docKey || ''), safeCode, safeRev); } catch (_) {}
      }
    }

    const codeApi = {
      async setDocPermissions(worldId, rawDocKey, perms) {
        const world = await getOrCreateWorld(worldId);
        const { docKey, doc } = ensureDocEntry(world, rawDocKey, true);
        if (typeof perms.canSeeCode === 'boolean') doc.canSeeCode = perms.canSeeCode;
        if (typeof perms.canEditCode === 'boolean') doc.canEditCode = perms.canEditCode;
        syncNativeCodeDoc(world, docKey, doc);
        await persistWorld(world);
        return { canSeeCode: doc.canSeeCode, canEditCode: doc.canEditCode };
      },
      async getLobbyCode(req) {
        const world = await getOrCreateWorld(req && req.worldId);
        const pathNorm = normalizeLobbyPath((req && req.path) || 'main.js');
        const { docKey, doc } = ensureDocEntry(world, 'lobby:' + pathNorm, true);
        if (!doc.canSeeCode) {
          return { ok: false, error: 'Permission denied: hidden code', canSeeCode: false, canEditCode: false, code: null, revision: doc.revision, docKey };
        }
        const files = {};
        for (const [k, d] of world.codeDocs.entries()) {
          if (k.startsWith('lobby:') && d.canSeeCode) files[k.slice('lobby:'.length)] = d.code;
        }
        return {
          ok: true,
          docKey,
          canSeeCode: true,
          canEditCode: doc.canEditCode,
          code: doc.code,
          revision: doc.revision,
          collabVersion: doc.revision,
          files,
        };
      },
      async getCodeBlock(req) {
        const world = await getOrCreateWorld(req && req.worldId);
        const docKey = makeCodeBlockDocKey(req.x, req.y, req.z);
        const { doc } = ensureDocEntry(world, docKey, true);
        if (!doc.canSeeCode) {
          return { ok: false, error: 'Permission denied: hidden code', canSeeCode: false, canEditCode: false, code: null, revision: doc.revision, docKey };
        }
        return {
          ok: true,
          docKey,
          canSeeCode: true,
          canEditCode: doc.canEditCode,
          code: doc.code,
          revision: doc.revision,
          collabVersion: doc.revision,
        };
      },
      async collabPull(req) {
        const world = await getOrCreateWorld(req && req.worldId);
        const { docKey, doc } = ensureDocEntry(world, req.docKey, true);
        if (!doc.canSeeCode) {
          return { ok: false, error: 'Permission denied: hidden code', canSeeCode: false, canEditCode: false, code: null, revision: doc.revision };
        }
        return {
          ok: true,
          docKey,
          code: doc.code,
          revision: doc.revision,
          clientID: String((req && req.clientID) || 'local_client_1'),
        };
      },
      async collabPush(req) {
        const world = await getOrCreateWorld(req && req.worldId);
        const { docKey, doc } = ensureDocEntry(world, req.docKey, true);
        if (!doc.canSeeCode || !doc.canEditCode) {
          return { ok: false, accepted: false, error: 'Permission denied: read-only document', revision: doc.revision };
        }
        const baseRev = Number(req.baseRevision != null ? req.baseRevision : req.version);
        if (Number.isFinite(baseRev) && baseRev !== doc.revision) {
          return { ok: false, accepted: false, conflict: true, error: 'Stale revision rejected', revision: doc.revision, code: doc.code };
        }

        const callerClientId = String((req && req.clientID) || 'local_client_1');
        let wireUpdatesPayload = null;
        if (req && req.updates !== undefined) {
          const parsed = tryParseCodeMirrorUpdates(req.updates);
          if (!parsed) {
            return { ok: false, accepted: false, error: 'Malformed CodeMirror updates payload', revision: doc.revision };
          }
          try {
            const applied = applyCodeMirrorUpdates(doc.code, parsed, callerClientId);
            doc.code = applied.code;
            doc.revision += applied.count;
            wireUpdatesPayload = applied.normalizedJson;
          } catch (err) {
            return { ok: false, accepted: false, error: err && err.message ? err.message : 'Invalid ChangeSet', revision: doc.revision };
          }
        } else if (req && Array.isArray(req.changes)) {
          try {
            doc.code = applyCodeMirrorChangeSet(doc.code, req.changes);
            doc.revision += 1;
            wireUpdatesPayload = JSON.stringify([{ clientID: callerClientId, changes: req.changes }]);
          } catch (err) {
            return { ok: false, accepted: false, error: err && err.message ? err.message : 'Invalid ChangeSet', revision: doc.revision };
          }
        } else {
          doc.code = String(req.code != null ? req.code : '');
          doc.revision += 1;
          wireUpdatesPayload = doc.code;
        }

        syncNativeCodeDoc(world, docKey, doc);
        await persistWorld(world);
        const peerSet = world.codePeers.get(docKey);
        if (peerSet) {
          for (const peer of peerSet) {
            if (peer.joined) {
              sendS2C(peer, op.kb || 94, {
                Qr: COLLAB_EVT.Updates,
                Kr: docKey,
                Al: doc.revision,
                Tl: wireUpdatesPayload,
                Mq: callerClientId,
              });
            }
          }
        }
        return {
          ok: true,
          accepted: true,
          docKey,
          code: doc.code,
          revision: doc.revision,
          version: doc.revision,
          collabVersion: doc.revision,
          clientID: callerClientId,
        };
      },
    };

    function buildLobbyFilesSnapshot(world) {
      const filesList = [];
      const filesMap = {};
      for (const [k, d] of world.codeDocs.entries()) {
        if (k.startsWith('lobby:')) {
          const rel = k.slice('lobby:'.length);
          filesList.push(rel);
          if (d.canSeeCode) filesMap[rel] = d.code;
        }
      }
      return { filesList, filesMap };
    }

    function handleCodeCollabMessage(world, session, msg) {
      try {
        const action = Number(msg && msg.Qr);
        const rawKey = String((msg && msg.Kr) || 'lobby:main.js');
        if (action === COLLAB_OP.CreateFile) {
          const targetKey = makeLobbyDocKey(msg.Yu || rawKey.replace(/^lobby:/, ''));
          const created = ensureDocEntry(world, targetKey, true);
          syncNativeCodeDoc(world, created.docKey, created.doc);
          const { filesList, filesMap } = buildLobbyFilesSnapshot(world);
          broadcastS2C(world, op.kb || 94, {
            Qr: COLLAB_EVT.LobbyFilesChanged,
            Kr: targetKey,
            g6: JSON.stringify(filesList),
            f6: JSON.stringify(filesMap),
          });
          persistWorld(world, session);
          return;
        }
        if (action === COLLAB_OP.RenameFile) {
          const fromRel = msg.Xu ? String(msg.Yu || '') : rawKey.replace(/^lobby:/, '');
          const toRel = msg.Xu ? String(msg.Xu || '') : String(msg.Yu || '');
          const fromKey = makeLobbyDocKey(fromRel);
          const toKey = makeLobbyDocKey(toRel);
          const existing = world.codeDocs.get(fromKey);
          if (existing && existing.canEditCode) {
            world.codeDocs.delete(fromKey);
            world.codeDocs.set(toKey, existing);
            if (nativeBridge && typeof nativeBridge.deleteCodeDoc === 'function') {
              try { nativeBridge.deleteCodeDoc(world.worldId, fromKey); } catch (_) {}
            }
            syncNativeCodeDoc(world, toKey, existing);
          }
          const { filesList, filesMap } = buildLobbyFilesSnapshot(world);
          broadcastS2C(world, op.kb || 94, {
            Qr: COLLAB_EVT.LobbyFilesChanged,
            Kr: toKey,
            g6: JSON.stringify(filesList),
            f6: JSON.stringify(filesMap),
          });
          persistWorld(world, session);
          return;
        }
        if (action === COLLAB_OP.RenameFolder) {
          const fromFolder = msg.Xu ? String(msg.Yu || '') : rawKey.replace(/^lobby:/, '');
          const toFolder = msg.Xu ? String(msg.Xu || '') : String(msg.Yu || '');
          const oldPrefix = 'lobby:' + normalizeLobbyFolderPath(fromFolder) + '/';
          const newPrefix = 'lobby:' + normalizeLobbyFolderPath(toFolder) + '/';
          for (const [k, v] of Array.from(world.codeDocs.entries())) {
            if (k.startsWith(oldPrefix) && v.canEditCode) {
              const movedKey = newPrefix + k.slice(oldPrefix.length);
              world.codeDocs.delete(k);
              world.codeDocs.set(movedKey, v);
              if (nativeBridge && typeof nativeBridge.deleteCodeDoc === 'function') {
                try { nativeBridge.deleteCodeDoc(world.worldId, k); } catch (_) {}
              }
              syncNativeCodeDoc(world, movedKey, v);
            }
          }
          const { filesList, filesMap } = buildLobbyFilesSnapshot(world);
          broadcastS2C(world, op.kb || 94, {
            Qr: COLLAB_EVT.LobbyFilesChanged,
            Kr: newPrefix.slice(0, -1),
            g6: JSON.stringify(filesList),
            f6: JSON.stringify(filesMap),
          });
          persistWorld(world, session);
          return;
        }
        if (action === COLLAB_OP.DeleteFile) {
          const targetSpec = msg.Yu ? ('lobby:' + String(msg.Yu)) : (rawKey.startsWith('lobby:') || rawKey.startsWith('codeBlock:') ? rawKey : 'lobby:' + rawKey);
          const delKey = validateDocKey(targetSpec);
          const cur = world.codeDocs.get(delKey);
          if (cur && cur.canEditCode) {
            world.codeDocs.delete(delKey);
            if (nativeBridge && typeof nativeBridge.deleteCodeDoc === 'function') {
              try { nativeBridge.deleteCodeDoc(world.worldId, delKey); } catch (_) {}
            }
          }
          const { filesList, filesMap } = buildLobbyFilesSnapshot(world);
          broadcastS2C(world, op.kb || 94, {
            Qr: COLLAB_EVT.LobbyFilesChanged,
            Kr: delKey,
            g6: JSON.stringify(filesList),
            f6: JSON.stringify(filesMap),
          });
          persistWorld(world, session);
          return;
        }
        if (action === COLLAB_OP.SetEngine) {
          world.codeEngine = String(msg.Xu || msg.Tl || 'default');
          broadcastS2C(world, op.kb || 94, { Qr: COLLAB_EVT.EngineSettings, Kr: rawKey, I6: world.codeEngine });
          persistWorld(world, session);
          return;
        }

        const { docKey, doc } = ensureDocEntry(world, rawKey, true);
        if (!world.codePeers.has(docKey)) world.codePeers.set(docKey, new Set());
        const peers = world.codePeers.get(docKey);

        if (action === COLLAB_OP.Join) {
          if (!doc.canSeeCode) {
            sendS2C(session, op.kb || 94, { Qr: COLLAB_EVT.ForceClose, Kr: docKey, Z2: 'Permission denied' });
            return;
          }
          peers.add(session);
          session.joinedDocs.add(docKey);
          const peerList = Array.from(peers).map((p) => ({ eId: p.eId, name: p.playerName }));
          const peerListJson = JSON.stringify(peerList);
          sendS2C(session, op.kb || 94, {
            Qr: COLLAB_EVT.Joined,
            Kr: docKey,
            Al: doc.revision,
            Rq: peers.size,
            Mq: peerListJson,
            Tl: doc.code,
            g6: '[]',
            I6: doc.engine || world.codeEngine,
          });
          for (const p of peers) {
            sendS2C(p, op.kb || 94, {
              Qr: COLLAB_EVT.Presence,
              Kr: docKey,
              Rq: peers.size,
              Mq: peerListJson,
              i6: peerListJson,
            });
          }
          return;
        }

        if (action === COLLAB_OP.Leave) {
          peers.delete(session);
          session.joinedDocs.delete(docKey);
          const peerList = Array.from(peers).map((p) => ({ eId: p.eId, name: p.playerName }));
          const peerListJson = JSON.stringify(peerList);
          for (const p of peers) {
            sendS2C(p, op.kb || 94, {
              Qr: COLLAB_EVT.Presence,
              Kr: docKey,
              Rq: peers.size,
              Mq: peerListJson,
              i6: peerListJson,
            });
          }
          return;
        }

        if (action === COLLAB_OP.Cursor) {
          const head = Number.isInteger(msg.Bl) ? msg.Bl : 0;
          const anchor = Number.isInteger(msg.Ul) ? msg.Ul : head;
          for (const p of peers) {
            if (p !== session && p.joined) {
              sendS2C(p, op.kb || 94, {
                Qr: COLLAB_EVT.RemoteCursor,
                Kr: docKey,
                Mq: session.eId,
                uq: session.eId,
                i6: session.playerName,
                Bl: head,
                Ul: anchor,
              });
            }
          }
          return;
        }

        if (action === COLLAB_OP.Push || action === COLLAB_OP.Submit || action === COLLAB_OP.ReloadAsVariation) {
          if (!doc.canSeeCode || !doc.canEditCode) {
            sendS2C(session, op.kb || 94, { Qr: COLLAB_EVT.ForceClose, Kr: docKey, Z2: 'Read-only document' });
            return;
          }
          if (msg.Al != null && Number(msg.Al) !== doc.revision) {
            const peerListJson = JSON.stringify(Array.from(peers).map((p) => ({ eId: p.eId, name: p.playerName })));
            sendS2C(session, op.kb || 94, {
              Qr: COLLAB_EVT.Joined,
              Kr: docKey,
              Al: doc.revision,
              Rq: peers.size,
              Mq: peerListJson,
              Tl: doc.code,
              g6: '[]',
              Z2: 'stale_revision',
            });
            return;
          }

          let wireTl = doc.code;
          if (typeof msg.Tl === 'string') {
            const parsedCm = action === COLLAB_OP.Push ? tryParseCodeMirrorUpdates(msg.Tl) : null;
            if (parsedCm) {
              const applied = applyCodeMirrorUpdates(doc.code, parsedCm, session.eId);
              doc.code = applied.code;
              doc.revision += applied.count;
              wireTl = applied.normalizedJson;
            } else {
              doc.code = msg.Tl;
              doc.revision += 1;
              wireTl = doc.code;
            }
          } else if (action === COLLAB_OP.Submit) {
            doc.revision += 1;
            wireTl = doc.code;
          }

          syncNativeCodeDoc(world, docKey, doc);
          for (const p of peers) {
            if (p.joined) {
              sendS2C(p, op.kb || 94, {
                Qr: COLLAB_EVT.Updates,
                Kr: docKey,
                Al: doc.revision,
                Tl: wireTl,
                Mq: session.eId,
                uq: session.eId,
              });
            }
          }
          persistWorld(world, session);
        }
      } catch (err) {
        sendS2C(session, op.kb || 94, {
          Qr: COLLAB_EVT.ForceClose,
          Kr: String((msg && msg.Kr) || ''),
          Z2: err && err.message ? err.message : 'Invalid code operation',
        });
      }
    }

    // ---- Session Gameplay Message Dispatcher ----
    function emitChatToSessionOrWorld(world, targetSession, content, chatterEId) {
      const msgObj = {
        chatMsgId: `chat_${nextChatId++}`,
        content: String(content),
        chatterEId: chatterEId || '0',
      };
      world.chatLog.push(msgObj);
      if (world.chatLog.length > 200) world.chatLog.shift();
      if (targetSession) sendS2C(targetSession, op.ab || 125, msgObj);
      else broadcastS2C(world, op.ab || 125, msgObj);
    }

    function applyDamage(world, session, amount) {
      if (!session.isAlive || world.gameMode === 'creative') return;
      const dmg = Math.max(0, Number(amount) || 0);
      session.health = Math.max(0, session.health - dmg);
      sendS2C(session, op.lc || 107, {
        eId: session.eId,
        B5: 100,
        Y5: session.health / 100,
      });
      if (session.health <= 0) {
        session.isAlive = false;
        session.vx = 0;
        session.vy = 0;
        session.vz = 0;
        broadcastS2C(world, op.wc || 106, {
          eId: session.eId,
          settings: { _isAlive: false, canSee: true },
        });
      }
      persistWorld(world, session);
    }

    function respawnSession(world, session) {
      session.health = 100;
      session.isAlive = true;
      session.x = DEFAULT_SPAWN[0];
      session.y = DEFAULT_SPAWN[1];
      session.z = DEFAULT_SPAWN[2];
      session.vx = 0;
      session.vy = 0;
      session.vz = 0;
      session.entity.x = session.x;
      session.entity.y = session.y;
      session.entity.z = session.z;
      // Local keep-inventory survival rule: preserve player inventory on death/respawn
      broadcastS2C(world, op.wc || 106, {
        eId: session.eId,
        settings: { _isAlive: true, canSee: true },
      });
      sendS2C(session, op.ve || 18, {
        zq: nextTeleportId++,
        sC: session.x,
        uC: session.y,
        mq: session.z,
      });
      sendS2C(session, op.lc || 107, {
        eId: session.eId,
        B5: 100,
        Y5: 1.0,
      });
      syncInventoryS2C(session);
      broadcastPatch(world);
      persistWorld(world, session);
    }

    function stepSessionPhysics(world, session, dtSec, controls) {
      if (!session.isAlive) return { x: session.x, y: session.y, z: session.z, grounded: false };
      const dt = Math.min(0.1, Math.max(0.001, Number(dtSec) || 0.05));
      const ctrl = controls || {};
      const flying = world.gameMode === 'creative' && !!ctrl.flying;
      if (typeof ctrl.vx === 'number') session.vx = ctrl.vx;
      if (typeof ctrl.vz === 'number') session.vz = ctrl.vz;

      if (flying) {
        session.vy = typeof ctrl.vy === 'number' ? ctrl.vy : 0;
      } else {
        if (ctrl.jump && session.onGround) {
          session.vy = 8.2;
          session.onGround = false;
        }
        session.vy = Math.max(-36, session.vy - 24 * dt);
      }

      // Resolve X, Z, then Y against solid voxel AABBs
      const tryX = session.x + session.vx * dt;
      if (!collidesWorldSolid(world, tryX, session.y, session.z)) session.x = tryX;
      else session.vx = 0;

      const tryZ = session.z + session.vz * dt;
      if (!collidesWorldSolid(world, session.x, session.y, tryZ)) session.z = tryZ;
      else session.vz = 0;

      const tryY = session.y + session.vy * dt;
      if (!collidesWorldSolid(world, session.x, tryY, session.z)) {
        session.y = tryY;
        session.onGround = false;
      } else {
        if (session.vy < 0) {
          session.y = Math.floor(tryY) + 1.0001;
          session.onGround = true;
        }
        session.vy = 0;
      }

      session.entity.x = session.x;
      session.entity.y = session.y;
      session.entity.z = session.z;
      session.entity.jumping = !session.onGround && session.vy > 0;
      session.entity.crouching = !!ctrl.crouch;
      session.entity.pose = ctrl.crouch ? 'crouching' : 'standing';
      collectNearbyDrops(world, session, 2.1);
      broadcastPatch(world);
      return { x: session.x, y: session.y, z: session.z, grounded: session.onGround };
    }

    function collidesWorldSolid(world, px, py, pz) {
      const halfW = 0.29;
      const height = 1.75;
      const minBx = Math.floor(px - halfW);
      const maxBx = Math.floor(px + halfW);
      const minBy = Math.floor(py);
      const maxBy = Math.floor(py + height - 0.001);
      const minBz = Math.floor(pz - halfW);
      const maxBz = Math.floor(pz + halfW);
      for (let bx = minBx; bx <= maxBx; bx++) {
        for (let by = minBy; by <= maxBy; by++) {
          for (let bz = minBz; bz <= maxBz; bz++) {
            if (isSolidBlock(getWorldBlock(world, bx, by, bz))) return true;
          }
        }
      }
      return false;
    }

    function handleCommand(world, session, rawText) {
      const parts = rawText.trim().split(/\s+/);
      const cmd = parts[0].toLowerCase();
      if (cmd === '/help') {
        emitChatToSessionOrWorld(world, session, 'Commands: /help /spawn /gamemode <survival|creative> /time [val] /seed /clear /give <item> [amt]', '0');
        return;
      }
      if (cmd === '/spawn') {
        session.x = DEFAULT_SPAWN[0];
        session.y = DEFAULT_SPAWN[1];
        session.z = DEFAULT_SPAWN[2];
        session.entity.x = session.x;
        session.entity.y = session.y;
        session.entity.z = session.z;
        sendS2C(session, op.ve || 18, { zq: nextTeleportId++, sC: session.x, uC: session.y, mq: session.z });
        broadcastPatch(world);
        emitChatToSessionOrWorld(world, session, 'Teleported to spawn.', '0');
        persistWorld(world, session);
        return;
      }
      if (cmd === '/gamemode') {
        const nextMode = (parts[1] || '').toLowerCase();
        if (nextMode === 'creative' || nextMode === 'c' || nextMode === '1') world.gameMode = 'creative';
        else if (nextMode === 'survival' || nextMode === 's' || nextMode === '0') world.gameMode = 'survival';
        else {
          emitChatToSessionOrWorld(world, session, 'Usage: /gamemode <survival|creative>', '0');
          return;
        }
        broadcastS2C(world, op.sc || 1, buildClientOptions(world.gameMode));
        emitChatToSessionOrWorld(world, session, `Game mode set to ${world.gameMode}.`, '0');
        persistWorld(world, session);
        return;
      }
      if (cmd === '/time') {
        if (parts[1] !== undefined && Number.isFinite(Number(parts[1]))) {
          world.worldTime = Number(parts[1]);
        }
        emitChatToSessionOrWorld(world, session, `World time: ${world.worldTime}`, '0');
        persistWorld(world, session);
        return;
      }
      if (cmd === '/seed') {
        emitChatToSessionOrWorld(world, session, `World seed: ${world.seed}`, '0');
        return;
      }
      if (cmd === '/clear') {
        session.inventory = new Array(INVENTORY_SLOTS).fill(null);
        syncInventoryS2C(session);
        broadcastPatch(world);
        emitChatToSessionOrWorld(world, session, 'Inventory cleared.', '0');
        persistWorld(world, session);
        return;
      }
      if (cmd === '/give') {
        const rest = parts.slice(1);
        if (rest.length === 0) {
          emitChatToSessionOrWorld(world, session, 'Usage: /give <item> [amount]', '0');
          return;
        }
        let amt = 1;
        let itemQuery = rest.join(' ');
        if (rest.length >= 2 && /^\d+$/.test(rest[rest.length - 1])) {
          amt = Math.max(1, Math.min(999, Number(rest[rest.length - 1])));
          itemQuery = rest.slice(0, -1).join(' ').replace(/^_+|_+$/g, '').replace(/_/g, ' ');
        } else {
          itemQuery = itemQuery.replace(/_/g, ' ');
        }
        const info = resolveItemInfo(itemQuery);
        if (!info) {
          emitChatToSessionOrWorld(world, session, `Unknown item: ${itemQuery}`, '0');
          return;
        }
        const given = addItemToInventory(session.inventory, info.name, amt);
        syncInventoryS2C(session);
        broadcastPatch(world);
        emitChatToSessionOrWorld(world, session, `Gave ${given}x ${info.name}.`, '0');
        persistWorld(world, session);
        return;
      }
      emitChatToSessionOrWorld(world, session, `Unknown command: ${cmd}`, '0');
    }

    function handleClientMessage(world, session, opcode, data) {
      // Ping Qc=6
      if (opcode === (op.Qc || 6)) {
        sendS2C(session, op.Qc || 6, (data && typeof data.jK === 'number') ? data.jK : 0);
        return;
      }
      // Render distance Vf=35 or chunk loaded Zf=8 or teleport ack rg=29
      if (opcode === (op.Vf || 35) || opcode === (op.Zf || 8) || opcode === (op.rg || 29)) {
        return;
      }
      // Chunk request Ah=103
      if (opcode === (op.Ah || 103)) {
        const chunkId = String((data && data.iH) || '0|0|0');
        const parts = chunkId.split('|');
        const cx = Number(parts[0]) || 0;
        const cy = Number(parts[1]) || 0;
        const cz = Number(parts[2]) || 0;
        const rle = buildChunk32Rle(cx, cy, cz, world.seedU32, world.overrides, nativeBridge);
        sendS2C(session, op.db || 24, {
          W9: chunkId,
          ci: false,
          q_: rle,
          sharedBlockData: null,
          F_: null,
          Sf: null,
        });
        return;
      }
      // Movement yh=111
      if (opcode === (op.yh || 111)) {
        if (!session.isAlive) {
          // Reject movement while dead
          sendS2C(session, op.ve || 18, { zq: nextTeleportId++, sC: session.x, uC: session.y, mq: session.z });
          return;
        }
        if (data && Array.isArray(data.vR) && data.vR.length >= 3) {
          const [nx, ny, nz] = data.vR;
          if (!collidesWorldSolid(world, nx, ny, nz)) {
            session.x = nx;
            session.y = ny;
            session.z = nz;
          }
        } else if (data && typeof data.Av === 'number' && typeof data.Tv === 'number' && typeof data.Qv === 'number') {
          if (!collidesWorldSolid(world, data.Av, data.Tv, data.Qv)) {
            session.x = data.Av;
            session.y = data.Tv;
            session.z = data.Qv;
          }
        }
        if (data && typeof data.RP === 'number') session.xRotation = data.RP;
        if (data && typeof data.loF === 'number') session.yRotation = data.loF;
        session.entity.x = session.x;
        session.entity.y = session.y;
        session.entity.z = session.z;
        session.entity.xRotation = session.xRotation;
        session.entity.yRotation = session.yRotation;
        session.entity.jumping = !!(data && data.yR);
        session.entity.crouching = !!(data && data.kR);
        session.entity.armSwinging = !!(data && data.jR);
        session.entity.pose = session.entity.crouching ? 'crouching' : 'standing';
        collectNearbyDrops(world, session, 2.1);
        broadcastPatch(world);
        persistWorld(world, session);
        return;
      }
      // Block edit Jf=43
      if (opcode === (op.Jf || 43)) {
        if (!session.isAlive || !data || !Array.isArray(data.bV) || data.bV.length < 3) return;
        const rawCoords = data.bV.map(Number);
        if (!rawCoords.slice(0, 3).every(Number.isFinite)) return;
        const [bx, by, bz] = rawCoords.slice(0, 3).map((n) => Math.floor(n));
        const dx = bx + 0.5 - session.x;
        const dy = by + 0.5 - (session.y + 1.0);
        const dz = bz + 0.5 - session.z;
        const curBlock = getWorldBlock(world, bx, by, bz);
        if (dx * dx + dy * dy + dz * dz > 64) {
          sendS2C(session, op.W || 50, [{ Z5: [bx, by, bz], P5: curBlock, o5: null }]);
          return;
        }
        const newBlockId = Number(data.jG) || 0;
        if (newBlockId === 0) {
          // Breaking block
          if (curBlock <= 0 || (curBlock === 147 && world.gameMode !== 'creative')) {
            sendS2C(session, op.W || 50, [{ Z5: [bx, by, bz], P5: curBlock, o5: null }]);
            return;
          }
          setWorldBlock(world, bx, by, bz, 0);
          broadcastS2C(world, op.A || 36, { o_: [bx, by, bz], Q_: 0 });
          if (world.gameMode !== 'creative') {
            const bInfo = itemById.get(curBlock);
            const dropId = bInfo ? bInfo.dropItemId : curBlock;
            spawnWorldDrop(world, dropId, 1, bx + 0.5, by + 0.25, bz + 0.5);
            broadcastPatch(world);
          }
          persistWorld(world, session);
        } else {
          // Placing block
          if (curBlock !== 0 || playerOverlapsBlock(session.x, session.y, session.z, bx, by, bz) || !hasAdjacentSolidOrOverride(world, bx, by, bz)) {
            sendS2C(session, op.W || 50, [{ Z5: [bx, by, bz], P5: curBlock, o5: null }]);
            return;
          }
          if (world.gameMode !== 'creative') {
            const placeInfo = itemById.get(newBlockId);
            const slotItem = session.inventory[session.selectedSlot];
            if (!slotItem || (placeInfo && slotItem.name !== placeInfo.name) || slotItem.amount < 1) {
              sendS2C(session, op.W || 50, [{ Z5: [bx, by, bz], P5: curBlock, o5: null }]);
              return;
            }
            slotItem.amount -= 1;
            if (slotItem.amount <= 0) session.inventory[session.selectedSlot] = null;
            syncInventoryS2C(session);
          }
          setWorldBlock(world, bx, by, bz, newBlockId);
          broadcastS2C(world, op.A || 36, { o_: [bx, by, bz], Q_: newBlockId });
          broadcastPatch(world);
          persistWorld(world, session);
        }
        return;
      }
      // Selected slot Eh=93
      if (opcode === (op.Eh || 93)) {
        const slotIdx = typeof data === 'number' ? data : Number(data);
        if (Number.isInteger(slotIdx) && slotIdx >= 0 && slotIdx < INVENTORY_SLOTS) {
          session.selectedSlot = slotIdx;
          syncHeldItemOnEntity(session);
          sendS2C(session, op.de || 105, session.selectedSlot);
          broadcastPatch(world);
          persistWorld(world, session);
        }
        return;
      }
      // Inventory swap ej=76
      if (opcode === (op.ej || 76)) {
        const a = Number(data && data.gH);
        const b = Number(data && data.Hs);
        if (Number.isInteger(a) && Number.isInteger(b) && a >= 0 && a < INVENTORY_SLOTS && b >= 0) {
          if (b >= INVENTORY_SLOTS && world.gameMode === 'creative') {
            const catIdx = b - INVENTORY_SLOTS;
            const catName = (worldMeta.creativeCatalog || [])[catIdx];
            const info = catName ? resolveItemInfo(catName) : null;
            if (info) {
              session.inventory[a] = { name: info.name, amount: info.maxStack, attributes: { customAttributes: {} } };
            }
          } else if (b < INVENTORY_SLOTS) {
            const tmp = session.inventory[a];
            session.inventory[a] = session.inventory[b];
            session.inventory[b] = tmp;
          }
          syncInventoryS2C(session);
          broadcastPatch(world);
          persistWorld(world, session);
        }
        return;
      }
      // Inventory move/merge th=5
      if (opcode === (op.th || 5)) {
        const from = Number(data && data.YV);
        const to = Number(data && data.XV);
        const amt = Math.max(1, Number(data && data.wV) || 1);
        if (Number.isInteger(from) && Number.isInteger(to) && from >= 0 && from < INVENTORY_SLOTS && to >= 0 && to < INVENTORY_SLOTS && from !== to) {
          const src = session.inventory[from];
          if (src && src.amount > 0) {
            const moveAmt = Math.min(amt, src.amount);
            const info = resolveItemInfo(src.name);
            const maxS = info ? info.maxStack : 999;
            const dst = session.inventory[to];
            if (!dst) {
              session.inventory[to] = { name: src.name, amount: moveAmt, attributes: JSON.parse(JSON.stringify(src.attributes || { customAttributes: {} })) };
              src.amount -= moveAmt;
              if (src.amount <= 0) session.inventory[from] = null;
            } else if (dst.name === src.name && dst.amount < maxS) {
              const fit = Math.min(moveAmt, maxS - dst.amount);
              dst.amount += fit;
              src.amount -= fit;
              if (src.amount <= 0) session.inventory[from] = null;
            }
            syncInventoryS2C(session);
            broadcastPatch(world);
            persistWorld(world, session);
          }
        }
        return;
      }
      // Inventory transfer xh=9
      if (opcode === (op.xh || 9)) {
        const from = Number(data && data.rK);
        const to = Number(data && data.bK);
        const amt = Math.max(1, Number(data && data.zK) || 1);
        if (Number.isInteger(from) && Number.isInteger(to) && from >= 0 && from < INVENTORY_SLOTS && to >= 0 && to < INVENTORY_SLOTS && from !== to) {
          handleClientMessage(world, session, op.th || 5, { YV: from, XV: to, wV: amt });
        }
        return;
      }
      // Item drop Ag=115
      if (opcode === (op.Ag || 115)) {
        const slot = Number(data && data.qH);
        const reqAmt = Math.max(1, Number(data && data.IH) || 1);
        if (Number.isInteger(slot) && slot >= 0 && slot < INVENTORY_SLOTS) {
          const item = session.inventory[slot];
          if (item && item.amount > 0) {
            const dropAmt = Math.min(reqAmt, item.amount);
            const info = resolveItemInfo(item.name);
            if (world.gameMode !== 'creative') {
              item.amount -= dropAmt;
              if (item.amount <= 0) session.inventory[slot] = null;
            }
            if (info) {
              spawnWorldDrop(world, info.id, dropAmt, session.x + 1.25, session.y + 0.3, session.z, item.attributes);
            }
            syncInventoryS2C(session);
            broadcastPatch(world);
            persistWorld(world, session);
          }
        }
        return;
      }
      // Crafting ug=117
      if (opcode === (op.ug || 117)) {
        const outName = String((data && data.VK) || '');
        const recipeIdx = Number(data && data.sK) || 0;
        const times = Math.max(1, Math.min(999, Number(data && data.jsF) || 1));
        const recipeList = recipesByOutput[outName];
        const recipe = Array.isArray(recipeList) ? recipeList[recipeIdx] : null;
        const outInfo = resolveItemInfo(outName);
        if (!recipe || !outInfo) return;
        const outPerCraft = Math.max(1, Number(recipe[0]) || 1);
        const ingredients = Array.isArray(recipe[1]) ? recipe[1] : [];

        if (world.gameMode !== 'creative') {
          for (const ing of ingredients) {
            const alts = Array.isArray(ing[0]) ? ing[0] : [String(ing[0])];
            const need = (Number(ing[1]) || 1) * times;
            if (countMatchingIngredient(session.inventory, alts) < need) {
              syncInventoryS2C(session);
              return;
            }
          }
          for (const ing of ingredients) {
            const alts = Array.isArray(ing[0]) ? ing[0] : [String(ing[0])];
            const need = (Number(ing[1]) || 1) * times;
            consumeMatchingIngredient(session.inventory, alts, need);
          }
        }
        addItemToInventory(session.inventory, outInfo.name, outPerCraft * times);
        syncInventoryS2C(session);
        broadcastPatch(world);
        persistWorld(world, session);
        return;
      }
      // Salvage Oi=10
      if (opcode === (op.Oi || 10)) {
        const slot = Number(data && data.Om);
        const reqAmt = Math.max(1, Number(data && data.Qm) || 1);
        if (!Number.isInteger(slot) || slot < 0 || slot >= INVENTORY_SLOTS) return;
        const item = session.inventory[slot];
        if (!item) return;
        const recipeList = recipesByOutput[item.name];
        const recipe = Array.isArray(recipeList) && recipeList.length > 0 ? recipeList[0] : null;
        if (!recipe) return;
        const outPerCraft = Math.max(1, Number(recipe[0]) || 1);
        const salvageSets = Math.floor(Math.min(reqAmt, item.amount) / outPerCraft);
        if (salvageSets <= 0) return;
        item.amount -= salvageSets * outPerCraft;
        if (item.amount <= 0) session.inventory[slot] = null;
        const ingredients = Array.isArray(recipe[1]) ? recipe[1] : [];
        for (const ing of ingredients) {
          const alts = Array.isArray(ing[0]) ? ing[0] : [String(ing[0])];
          const retAmt = Math.max(1, Math.floor((Number(ing[1]) || 1) * salvageSets));
          if (alts[0]) addItemToInventory(session.inventory, alts[0], retAmt);
        }
        syncInventoryS2C(session);
        broadcastPatch(world);
        persistWorld(world, session);
        return;
      }
      // Respawn Ki=15
      if (opcode === (op.Ki || 15)) {
        respawnSession(world, session);
        return;
      }
      // Chat / Slash commands Nf=38
      if (opcode === (op.Nf || 38)) {
        const text = String((data && data.Nk) || '');
        if (!text) return;
        if (text.startsWith('/')) {
          handleCommand(world, session, text);
        } else {
          emitChatToSessionOrWorld(world, null, text, session.eId);
        }
        return;
      }
      // Code Collaboration kg=2
      if (opcode === (op.kg || 2)) {
        handleCodeCollabMessage(world, session, data || {});
      }
    }

    // ---- In-Process LocalWebSocket ----
    function parseRoomUrl(rawUrl) {
      let urlObj;
      try {
        urlObj = new URL(String(rawUrl), 'wss://game-server-local1.bloxd.io');
      } catch (_) {
        urlObj = { pathname: '/game_room_classic__local_world', searchParams: new URLSearchParams() };
      }
      const parts = urlObj.pathname.split('/').filter(Boolean);
      const roomSegment = parts[parts.length - 1] || 'game_room_classic__local_world';
      const sessionId = urlObj.searchParams.get('sessionId') || `sess_${Math.random().toString(36).slice(2, 8)}`;
      const isSocial = roomSegment.includes('social');
      let mode = 'survival';
      let worldId = 'local_world';
      if (!isSocial && roomSegment.startsWith('game_room_')) {
        const rest = roomSegment.slice('game_room_'.length);
        const idx = rest.indexOf('__');
        if (idx >= 0) {
          mode = safeDecodeUriSegment(rest.slice(0, idx)) || 'classic';
          worldId = safeDecodeUriSegment(rest.slice(idx + 2)) || 'local_world';
        } else {
          worldId = safeDecodeUriSegment(rest);
        }
      } else if (!isSocial) {
        worldId = safeDecodeUriSegment(roomSegment);
      }
      return { url: String(rawUrl), roomId: roomSegment, sessionId, isSocial, mode, worldId };
    }

    class LocalWebSocket {
      constructor(url, protocols) {
        this.url = String(url || '');
        this.protocol = Array.isArray(protocols) ? (protocols[0] || '') : (protocols || '');
        this.extensions = '';
        this.bufferedAmount = 0;
        this.binaryType = 'arraybuffer';
        this.readyState = LocalWebSocket.CONNECTING;
        this.onopen = null;
        this.onmessage = null;
        this.onerror = null;
        this.onclose = null;
        this._listeners = { open: new Set(), message: new Set(), error: new Set(), close: new Set() };
        this._session = null;

        queueMicrotask(() => {
          this._connectInternal().catch((err) => {
            this._emit('error', { type: 'error', error: err });
            this.close(1011, err && err.message ? err.message : 'Internal local room error');
          });
        });
      }

      addEventListener(type, fn) {
        if (this._listeners[type] && typeof fn === 'function') this._listeners[type].add(fn);
      }
      removeEventListener(type, fn) {
        if (this._listeners[type]) this._listeners[type].delete(fn);
      }
      _emit(type, ev) {
        const prop = this['on' + type];
        if (typeof prop === 'function') prop.call(this, ev);
        const set = this._listeners[type];
        if (set) for (const fn of Array.from(set)) fn.call(this, ev);
      }

      async _connectInternal() {
        if (this.readyState !== LocalWebSocket.CONNECTING) return;
        const route = parseRoomUrl(this.url);
        this.readyState = LocalWebSocket.OPEN;
        this._emit('open', { type: 'open', target: this });

        if (route.isSocial) {
          this._isSocial = true;
          this._deliver(buildJoinRoomFrame(route.sessionId, 'none', new Uint8Array(0)));
          return;
        }

        const world = await getOrCreateWorld(route.worldId, route.mode);
        const eId = world.sessions.size === 0 ? '1' : String(++nextEntityNum);
        const savedP = eId === '1' ? world.savedPlayer : null;

        const entity = new rt.EntityState();
        entity.type = 'Player';
        entity.name = persistedPlayerName;
        entity.x = savedP && Number.isFinite(savedP.x) ? savedP.x : DEFAULT_SPAWN[0];
        entity.y = savedP && Number.isFinite(savedP.y) ? savedP.y : DEFAULT_SPAWN[1];
        entity.z = savedP && Number.isFinite(savedP.z) ? savedP.z : DEFAULT_SPAWN[2];
        entity.xRotation = (savedP && Number.isFinite(savedP.xRotation)) ? savedP.xRotation : 0;
        entity.yRotation = (savedP && Number.isFinite(savedP.yRotation)) ? savedP.yRotation : 0;

        const inventory = savedP && Array.isArray(savedP.inventory) && savedP.inventory.length === INVENTORY_SLOTS
          ? JSON.parse(JSON.stringify(savedP.inventory))
          : createStarterInventory();
        const selectedSlot = savedP && Number.isInteger(savedP.selectedSlot) ? savedP.selectedSlot : 0;
        entity.heldItemName = inventory[selectedSlot] ? inventory[selectedSlot].name : '';

        world.state.entities.set(eId, entity);

        const session = {
          ws: this,
          world,
          roomId: route.roomId,
          sessionId: route.sessionId,
          eId,
          playerName: entity.name,
          entity,
          x: entity.x,
          y: entity.y,
          z: entity.z,
          vx: 0,
          vy: 0,
          vz: 0,
          onGround: true,
          xRotation: entity.xRotation,
          yRotation: entity.yRotation,
          health: savedP && typeof savedP.health === 'number' ? savedP.health : 100,
          isAlive: savedP && typeof savedP.isAlive === 'boolean' ? savedP.isAlive : true,
          selectedSlot,
          inventory,
          joined: false,
          joinedDocs: new Set(),
          cipherAdjustments: defaultCipherAdjustments.slice(),
          detectedJsCipher: null,
          deliverFrame: (u8) => this._deliver(u8),
        };
        this._session = session;
        world.sessions.set(session.sessionId + ':' + eId, session);

        const hsBytes = rt.schema.Reflection.encode(world.encoder);
        this._deliver(buildJoinRoomFrame(route.sessionId, 'schema', hsBytes));
      }

      _deliver(u8) {
        if (this.readyState !== LocalWebSocket.OPEN) return;
        const copy = u8.slice();
        const data = this.binaryType === 'arraybuffer'
          ? copy.buffer.slice(copy.byteOffset, copy.byteOffset + copy.byteLength)
          : copy;
        queueMicrotask(() => {
          if (this.readyState === LocalWebSocket.OPEN) {
            this._emit('message', { type: 'message', data, target: this });
          }
        });
      }

      setCipherAdjustments(adjArray) {
        if (this._session) {
          this._session.cipherAdjustments = Array.isArray(adjArray)
            ? adjArray.map((n) => Number(n) & 0xff)
            : [];
        }
      }

      send(rawData) {
        if (this.readyState !== LocalWebSocket.OPEN) throw new Error('WebSocket is not open');
        if (this._isSocial) return;
        if (typeof Blob !== 'undefined' && rawData instanceof Blob) {
          rawData.arrayBuffer().then((buf) => {
            if (this.readyState === LocalWebSocket.OPEN) this.send(new Uint8Array(buf));
          }).catch(() => {});
          return;
        }
        const session = this._session;
        if (!session) return;
        const world = session.world;
        const decoded = decodeClientWireFrame(rt, c2sCodecs, rawData, {
          adjustments: session.cipherAdjustments,
          knownOpcodes: knownC2SOpcodes,
          detectedJsCipher: session.detectedJsCipher,
        });
        if (decoded.kind === 'join_ack') {
          if (session.joined) return;
          session.joined = true;
          if (typeof window !== 'undefined') {
            window.__fableRuntimeStats = window.__fableRuntimeStats || { errors: 0, wsJoins: 0 };
            window.__fableRuntimeStats.wsJoins = (window.__fableRuntimeStats.wsJoins || 0) + 1;
            window.__fableRuntimeStats.lastRoom = world.worldId + ':' + world.gameMode;
            console.log('[FABLE-ROOM-JOIN] world=' + world.worldId + ' mode=' + world.gameMode + ' joins=' + window.__fableRuntimeStats.wsJoins);
          }
          const fullState = world.encoder.encodeAll();
          session.deliverFrame(buildRoomStateFrame(fullState));
          // Send initialization burst
          sendS2C(session, op.sc || 1, buildClientOptions(world.gameMode));
          sendS2C(session, op.Tb || 64, { aE: session.eId, qE: session.cipherAdjustments.slice() });
          sendS2C(session, op.oc || 56, { FE: false, pE: world.seed, Bk: [], ck: null });
          sendS2C(session, op.wc || 106, {
            eId: session.eId,
            settings: { _isAlive: session.isAlive, canSee: true },
          });
          sendS2C(session, op.ve || 18, {
            zq: nextTeleportId++,
            sC: session.x,
            uC: session.y,
            mq: session.z,
          });
          syncInventoryS2C(session);
          sendS2C(session, op.lc || 107, {
            eId: session.eId,
            B5: 100,
            Y5: session.health / 100,
          });
          // Notify other connected local sessions of the newly joined entity
          broadcastPatch(world);
          persistWorld(world, session);
          return;
        }
        if (decoded.kind === 'leave') {
          this.close(1000, 'Client left room');
          return;
        }
        if (decoded.kind === 'message') {
          if (decoded.unambiguous && (decoded.cipherMode === 'js9445' || decoded.cipherMode === 'wasm9423')) {
            session.detectedJsCipher = decoded.cipherMode === 'js9445';
          }
          handleClientMessage(world, session, decoded.opcode, decoded.data);
        }
      }

      close(code, reason) {
        if (this.readyState === LocalWebSocket.CLOSING || this.readyState === LocalWebSocket.CLOSED) return;
        this.readyState = LocalWebSocket.CLOSING;
        const session = this._session;
        if (session) {
          const world = session.world;
          for (const docKey of session.joinedDocs) {
            const set = world.codePeers.get(docKey);
            if (set) set.delete(session);
          }
          session.joinedDocs.clear();
          persistWorld(world, session);
          world.sessions.delete(session.sessionId + ':' + session.eId);
          world.state.entities.delete(session.eId);
          broadcastPatch(world);
          this._session = null;
        }
        this.readyState = LocalWebSocket.CLOSED;
        queueMicrotask(() => {
          this._emit('close', {
            type: 'close',
            code: code || 1000,
            reason: reason || '',
            wasClean: true,
            target: this,
          });
        });
      }
    }
    LocalWebSocket.CONNECTING = 0;
    LocalWebSocket.OPEN = 1;
    LocalWebSocket.CLOSING = 2;
    LocalWebSocket.CLOSED = 3;
    LocalWebSocket.prototype.CONNECTING = 0;
    LocalWebSocket.prototype.OPEN = 1;
    LocalWebSocket.prototype.CLOSING = 2;
    LocalWebSocket.prototype.CLOSED = 3;

    function setPlayerName(rawName) {
      const next = normalizeUsername(rawName, persistedPlayerName);
      persistedPlayerName = next;
      if (typeof localStorage !== 'undefined') {
        try {
          localStorage.setItem('fable_local_player_name', next);
          localStorage.setItem('fable_player_name', next);
        } catch (_) {}
      }
      if (nativeBridge && typeof nativeBridge.setPlayerName === 'function') {
        try { nativeBridge.setPlayerName(next); } catch (_) {}
      }
      for (const world of worlds.values()) {
        for (const sess of world.sessions.values()) {
          if (sess.eId === '1') {
            sess.playerName = next;
            sess.entity.name = next;
            persistWorld(world, sess);
          }
        }
        broadcastPatch(world);
      }
      return next;
    }

    return {
      PROTOCOL,
      CIPHER_VERSION_PARAM,
      COLLAB_OP,
      COLLAB_EVT,
      rt,
      s2cCodecs,
      c2sCodecs,
      LocalWebSocket,
      setPlayerName,
      getPlayerName: () => persistedPlayerName,
      getOrCreateWorld,
      getWorldBlock,
      applyDamage,
      stepSessionPhysics,
      collectNearbyDrops,
      codeApi,
      CAPTURED_JS_EFFECTIVE_BASE,
      cipherOpcode,
      cipherPayloadBytes,
      encodeWasmClientBytesFrame: (opcode, avroBytes, adjustments) => encodeWasmClientBytesFrame(rt, opcode, avroBytes, adjustments),
      encodeWasmClientDataFrame: (opcode, payload, adjustments) => encodeWasmClientDataFrame(rt, opcode, payload, adjustments),
      encodeCapturedJsClientBytesFrame: (opcode, avroBytes, adjustments) => encodeCapturedJsClientBytesFrame(rt, opcode, avroBytes, adjustments),
      encodeCapturedJsClientDataFrame: (opcode, payload, adjustments) => encodeCapturedJsClientDataFrame(rt, opcode, payload, adjustments),
      decodeClientWireFrame: (rawBytes, decodeOpts) => decodeClientWireFrame(rt, c2sCodecs, rawBytes, {
        knownOpcodes: knownC2SOpcodes,
        ...(decodeOpts || {}),
      }),
      encodeClientMessage(opcode, payload, useWasmCipher, adjustments) {
        const codec = c2sCodecs.get(String(opcode));
        if (useWasmCipher === 'js' || useWasmCipher === 'captured_js') {
          if (codec) return encodeCapturedJsClientBytesFrame(rt, opcode, codec.encode(payload), adjustments);
          return encodeCapturedJsClientDataFrame(rt, opcode, payload, adjustments);
        }
        if (codec) {
          const avro = codec.encode(payload);
          return useWasmCipher !== false
            ? encodeWasmClientBytesFrame(rt, opcode, avro, adjustments)
            : buildRoomDataBytesFrame(rt, opcode, avro);
        }
        return useWasmCipher !== false
          ? encodeWasmClientDataFrame(rt, opcode, payload, adjustments)
          : buildRoomDataFrame(rt, opcode, payload);
      },
      decodeServerFrame(rawFrame) {
        const u8 = rawFrame instanceof Uint8Array
          ? rawFrame
          : (rawFrame instanceof ArrayBuffer ? new Uint8Array(rawFrame) : Uint8Array.from(rawFrame));
        const proto = u8[0];
        if (proto === PROTOCOL.JOIN_ROOM) {
          const it = { offset: 1 };
          const sLen = u8[it.offset++];
          const sessionId = rt.schema.decode.utf8Read(u8, it, sLen);
          const zLen = u8[it.offset++];
          const serializerId = rt.schema.decode.utf8Read(u8, it, zLen);
          const handshake = u8.subarray(it.offset);
          return { kind: 'JOIN_ROOM', sessionId, serializerId, handshake };
        }
        if (proto === PROTOCOL.ROOM_STATE) {
          return { kind: 'ROOM_STATE', bytes: u8.subarray(1) };
        }
        if (proto === PROTOCOL.ROOM_STATE_PATCH) {
          return { kind: 'ROOM_STATE_PATCH', bytes: u8.subarray(1) };
        }
        if (proto === PROTOCOL.ROOM_DATA) {
          const it = { offset: 1 };
          const opcode = rt.schema.decode.stringCheck(u8, it)
            ? rt.schema.decode.string(u8, it)
            : rt.schema.decode.number(u8, it);
          const data = u8.length > it.offset ? rt.msgpack.unpack(u8.subarray(it.offset)) : undefined;
          return { kind: 'ROOM_DATA', opcode, data };
        }
        if (proto === PROTOCOL.ROOM_DATA_BYTES) {
          const it = { offset: 1 };
          const opcode = rt.schema.decode.number(u8, it);
          const bytes = u8.subarray(it.offset);
          const codec = s2cCodecs.get(String(opcode));
          const data = codec ? codec.decode(bytes, 0) : bytes;
          return { kind: 'ROOM_DATA_BYTES', opcode, data, rawBytes: bytes };
        }
        return { kind: 'OTHER', proto };
      },
    };
  }

  // ---- Browser Auto-Bootstrap (strictly preserves window.fetch) ----
  async function bootBrowserEngine() {
    if (typeof window === 'undefined' || typeof document === 'undefined') return;
    const scriptEl = document.querySelector('script[data-bloxd-main]');
    const mainSrc = scriptEl ? scriptEl.getAttribute('data-bloxd-main') : '/static/js/7rp1w.main.57cd8edd.js';
    const stats = (window.__fableRuntimeStats = window.__fableRuntimeStats || {
      booted: false,
      wsInstalled: false,
      seeded: false,
      errors: 0,
      wsJoins: 0,
      lastRoom: '',
    });

    if (!window.__fableDiagInstalled) {
      window.__fableDiagInstalled = true;
      window.addEventListener('error', (ev) => {
        stats.errors += 1;
        console.error('[FABLE-ERR] window.error: ' + (ev && ev.message ? ev.message : String(ev)) + ' @ ' + (ev && ev.filename ? ev.filename + ':' + ev.lineno : 'unknown'));
      });
      window.addEventListener('unhandledrejection', (ev) => {
        stats.errors += 1;
        const r = ev && ev.reason;
        console.error('[FABLE-ERR] unhandledrejection: ' + (r && r.stack ? r.stack : String(r)));
      });
    }

    // Step 1: Load core protocol/world metadata and install LocalWebSocket FIRST
    // so IndexedDB issues can NEVER prevent the offline engine from attaching.
    let engineReady = false;
    try {
      const [protoRes, metaRes] = await Promise.all([
        window.fetch('/bloxd_protocol_map.json'),
        window.fetch('/bloxd_world_meta.json'),
      ]);
      const [protocolMap, worldMeta] = await Promise.all([
        protoRes.json(),
        metaRes.json(),
      ]);

      try {
        const nb = window.FableLocalNative;
        const nativeName = nb && typeof nb.getPlayerName === 'function' ? (nb.getPlayerName() || '') : '';
        const lsName = (typeof localStorage !== 'undefined' ? (localStorage.getItem('fable_local_player_name') || localStorage.getItem('fable_player_name') || '') : '');
        if (nativeName && !lsName && typeof localStorage !== 'undefined') {
          localStorage.setItem('fable_local_player_name', nativeName);
          localStorage.setItem('fable_player_name', nativeName);
        } else if (lsName && !nativeName && nb && typeof nb.setPlayerName === 'function') {
          nb.setPlayerName(lsName);
        }
      } catch (_) {}

      const engine = await createLocalBloxdEngine({ protocolMap, worldMeta });
      window.__fableLocalEngine = engine;
      window.__fableSetPlayerName = (name) => engine.setPlayerName(name);
      window.WebSocket = engine.LocalWebSocket;
      stats.wsInstalled = true;
      engineReady = true;
    } catch (err) {
      stats.errors += 1;
      console.error('[FABLE-ERR] Engine bootstrap failed:', err);
    }

    // Step 2: Best-effort bounded atlas seed (versioned so subsequent launches skip 1.7 MB rewrite)
    const SEED_VER = 'fable_seed_v1';
    try {
      const alreadySeeded = await hasSeededBrowserLocalForage(SEED_VER);
      if (alreadySeeded) {
        stats.seeded = true;
      } else {
        const seedRes = await window.fetch('/localforage_seed.json');
        if (seedRes && seedRes.ok) {
          const rawSeed = await seedRes.json();
          const preparedSeed = prepareAtlasSeed(rawSeed);
          stats.seeded = await seedBrowserLocalForage(preparedSeed, SEED_VER);
        }
      }
    } catch (seedErr) {
      console.warn('[FableLocalEngine] Atlas seed skipped:', seedErr);
    }

    const nativeStatus = (window.FableLocalNative && typeof window.FableLocalNative.isNativeReady === 'function')
      ? String(window.FableLocalNative.isNativeReady())
      : 'unavailable';
    stats.booted = engineReady;
    console.log('[FABLE-BOOT] engineReady=' + engineReady + ' ws=' + stats.wsInstalled + ' seeded=' + stats.seeded + ' native=' + nativeStatus + ' url=' + location.href);

    if (engineReady && mainSrc && !document.querySelector(`script[src="${mainSrc}"]`)) {
      const mainScript = document.createElement('script');
      mainScript.defer = true;
      mainScript.src = mainSrc;
      mainScript.onerror = () => {
        stats.errors += 1;
        console.error('[FABLE-ERR] Failed to load main bundle: ' + mainSrc);
      };
      document.head.appendChild(mainScript);

      // Emit periodic health telemetry so Android logcat/CI can verify real mount, canvas, and room join
      const emitHealth = (tag) => {
        try {
          const rootEl = document.getElementById('root');
          const hasPreload = !!(rootEl && rootEl.querySelector('.PreloadLoader'));
          const canvas = document.querySelector('canvas');
          const cw = canvas ? (canvas.width || 0) : 0;
          const ch = canvas ? (canvas.height || 0) : 0;
          console.log('[FABLE-HEALTH] t=' + tag + ' mounted=' + (!hasPreload && !!rootEl && rootEl.childElementCount > 0) + ' canvas=' + cw + 'x' + ch + ' wsJoins=' + stats.wsJoins + ' room=' + (stats.lastRoom || 'none') + ' errs=' + stats.errors + ' path=' + location.pathname + location.search);
        } catch (_) {}
      };
      setTimeout(() => emitHealth('4s'), 4000);
      setTimeout(() => emitHealth('10s'), 10000);
      setTimeout(() => emitHealth('18s'), 18000);
    }
  }

  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    bootBrowserEngine();
  }

  const exported = {
    PROTOCOL,
    CIPHER_VERSION_PARAM,
    CAPTURED_JS_XOR_MASK,
    CAPTURED_JS_EFFECTIVE_BASE,
    cipherOpcode,
    cipherPayloadBytes,
    normalizeUsername,
    prepareAtlasSeed,
    createAvroSchemaCodec,
    chunkIndex,
    rleEncode,
    rleDecode,
    seedStringToUint32,
    surfaceHeightAt,
    baseBlockAt,
    generateChunk32,
    buildChunk32Rle,
    isValidCapturedLobbyPath,
    normalizeLobbyFolderPath,
    makeLobbyDocKey,
    makeCodeBlockDocKey,
    validateDocKey,
    applyCodeMirrorChangeSet,
    tryParseCodeMirrorUpdates,
    applyCodeMirrorUpdates,
    createPersistenceStore,
    loadChunk156Runtime,
    createLocalBloxdEngine,
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = exported;
  } else if (root) {
    root.FableLocalBloxdEngine = exported;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
