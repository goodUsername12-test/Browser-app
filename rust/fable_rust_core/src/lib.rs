use std::ffi::c_void;

#[no_mangle]
pub extern "C" fn Java_com_fable_browser_engine_RustFastCore_nativeRustVersion(
    _env: *mut c_void,
    _cls: *mut c_void,
) -> i32 {
    40
}

#[no_mangle]
pub extern "C" fn Java_com_fable_browser_engine_RustFastCore_nativeComputePowerPlan(
    _env: *mut c_void,
    _cls: *mut c_void,
    battery_pct: i32,
    is_charging: u8,
    thermal_status: i32,
    master_saver: u8,
    auto_low_pct: i32,
    thermal_guard: u8,
    adaptive_hz: u8,
    game_max_fps: i32,
    game_render_scale_pct: i32,
    eco_cpu_cores: u8,
    idle_game_fps: i32,
    idle_timeout_sec: i32,
    is_game: u8,
    idle_ms: i64,
    high_refresh_pref: u8,
) -> i64 {
    let charging = is_charging != 0;
    let master = master_saver != 0;
    let t_guard = thermal_guard != 0;
    let adap_hz = adaptive_hz != 0;
    let eco_cpu = eco_cpu_cores != 0;
    let game = is_game != 0;
    let hi_hz = high_refresh_pref != 0;

    let low_battery_triggered = !charging && auto_low_pct > 0 && battery_pct >= 1 && battery_pct <= auto_low_pct;
    let saver_active = master || low_battery_triggered;
    let hot_moderate = t_guard && thermal_status >= 2;
    let hot_severe = t_guard && thermal_status >= 3;

    let clamped_timeout = idle_timeout_sec.clamp(3, 120) as i64;
    let is_idle = idle_ms >= clamped_timeout * 1000;

    // 1. Target Display Refresh Rate (Hz)
    let mut target_hz: i32 = if !hi_hz {
        60
    } else if game {
        game_max_fps.clamp(30, 120)
    } else if saver_active || hot_moderate {
        60
    } else if adap_hz && idle_ms >= 2500 {
        60
    } else {
        120
    };

    if hot_severe && target_hz > 60 {
        target_hz = 60;
    } else if hot_moderate && target_hz > 90 {
        target_hz = 90;
    }

    // 2. Target In-Game FPS Cap
    let mut target_game_fps: i32 = if game {
        let mut cap = game_max_fps.clamp(15, 120);
        if saver_active && master && game_max_fps >= 120 && cap > 90 {
            cap = 90;
        }
        if hot_severe && cap > 60 {
            cap = 60;
        } else if hot_moderate && cap > 90 {
            cap = 90;
        }
        if idle_game_fps > 0 && is_idle && idle_game_fps < cap {
            cap = idle_game_fps.clamp(15, 60);
            if target_hz > 60 && cap <= 60 {
                target_hz = 60;
            }
        }
        cap
    } else if saver_active {
        60
    } else {
        120
    };

    if target_game_fps < 15 {
        target_game_fps = 15;
    }

    // 3. 3D Render Resolution Scale % (50..100)
    let mut render_scale: i32 = if game {
        let mut sc = game_render_scale_pct.clamp(50, 100);
        if hot_severe && sc > 75 {
            sc = 75;
        } else if low_battery_triggered && sc == 100 {
            sc = 85;
        }
        sc
    } else {
        100
    };
    render_scale = render_scale.clamp(50, 100);

    // 4. CPU Boost Mode: 0 = Eco, 1 = Balanced, 2 = Cortex Big-Core Turbo
    let cpu_mode: i32 = if !game {
        0
    } else if eco_cpu || saver_active || hot_severe || is_idle {
        0
    } else if hot_moderate {
        1
    } else {
        2
    };

    // 5. Hardware Choreographer Monitor Active (only in active non-idle games when not in eco saver)
    let choreo: i64 = if game && !is_idle && !saver_active && !hot_severe && cpu_mode == 2 {
        1
    } else {
        0
    };

    let saver_bit: i64 = if saver_active { 1 } else { 0 };

    ((target_hz as i64) & 0xFF)
        | (((target_game_fps as i64) & 0xFF) << 8)
        | (((render_scale as i64) & 0xFF) << 16)
        | (((cpu_mode as i64) & 0x0F) << 24)
        | (choreo << 28)
        | (saver_bit << 29)
}

#[no_mangle]
pub extern "C" fn Java_com_fable_browser_engine_RustFastCore_nativeRecommendNetworkPlan(
    _env: *mut c_void,
    _cls: *mut c_void,
    rssi_dbm: i32,
    link_mbps: i32,
    down_kbps: i32,
    up_kbps: i32,
    freq_mhz: i32,
    is_wifi: u8,
    is_metered: u8,
    fast_conn: u8,
    file_size_mb: i32,
) -> i64 {
    let wifi = is_wifi != 0;
    let metered = is_metered != 0;
    let fast = fast_conn != 0;

    // 1. Signal & Link Quality Score (0..100)
    let mut score: i32 = if wifi {
        let rssi_score = ((rssi_dbm + 95) * 2).clamp(10, 70);
        let band_bonus = if freq_mhz >= 5900 {
            20
        } else if freq_mhz >= 4900 {
            15
        } else {
            5
        };
        let speed_bonus = (link_mbps / 40).clamp(0, 15);
        (rssi_score + band_bonus + speed_bonus).clamp(5, 100)
    } else {
        let down_mbps = down_kbps / 1000;
        (35 + down_mbps.clamp(0, 55)).clamp(15, 90)
    };

    if metered && score > 75 {
        score = 75;
    }

    // 2. Parallel Download Streams (1..6)
    let streams: i32 = if !fast || file_size_mb < 2 || metered {
        1
    } else if score >= 85 && file_size_mb >= 10 {
        6
    } else if score >= 70 && file_size_mb >= 4 {
        4
    } else if score >= 50 {
        2
    } else {
        1
    };

    // 3. Optimal Socket / Stream Buffer KB (64..512 KB)
    let buf_kb: i32 = if score >= 80 {
        512
    } else if score >= 55 {
        256
    } else {
        128
    };

    // 4. Optimal Upload Chunk KB (128..2048 KB)
    let up_mbps = (up_kbps / 1000).max(link_mbps / 2);
    let upload_chunk_kb: i32 = if up_mbps >= 80 && score >= 80 {
        2048
    } else if up_mbps >= 30 && score >= 60 {
        1024
    } else if up_mbps >= 10 {
        512
    } else {
        256
    };

    ((score as i64) & 0xFF)
        | (((streams as i64) & 0xFF) << 8)
        | (((buf_kb as i64) & 0xFFFF) << 16)
        | (((upload_chunk_kb as i64) & 0xFFFF) << 32)
}
