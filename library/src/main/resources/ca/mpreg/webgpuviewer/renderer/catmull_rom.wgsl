fn catmull_rom_weights(t: f32) -> array<f32, 4> {
    let t2 = t * t;
    let t3 = t2 * t;

    return array<f32, 4>(
        -0.5 * t3 + t2 - 0.5 * t,
         1.5 * t3 - 2.5 * t2 + 1.0,
        -1.5 * t3 + 2.0 * t2 + 0.5 * t,
         0.5 * t3 - 0.5 * t2
    );
}

fn catmull_rom_fast_unrolled(
    tex: texture_2d<f32>,
    p_start: vec2<i32>,
    wx: array<f32, 4>,
    wy: array<f32, 4>
) -> vec4<f32> {
    let r0 = to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x,     p_start.y), 0)) * wx[0]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 1, p_start.y), 0)) * wx[1]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 2, p_start.y), 0)) * wx[2]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 3, p_start.y), 0)) * wx[3];
    let r1 = to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x,     p_start.y + 1), 0)) * wx[0]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 1, p_start.y + 1), 0)) * wx[1]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 2, p_start.y + 1), 0)) * wx[2]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 3, p_start.y + 1), 0)) * wx[3];
    let r2 = to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x,     p_start.y + 2), 0)) * wx[0]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 1, p_start.y + 2), 0)) * wx[1]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 2, p_start.y + 2), 0)) * wx[2]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 3, p_start.y + 2), 0)) * wx[3];
    let r3 = to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x,     p_start.y + 3), 0)) * wx[0]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 1, p_start.y + 3), 0)) * wx[1]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 2, p_start.y + 3), 0)) * wx[2]
           + to_linear_exact(textureLoad(tex, vec2<i32>(p_start.x + 3, p_start.y + 3), 0)) * wx[3];

    return r0 * wy[0] + r1 * wy[1] + r2 * wy[2] + r3 * wy[3];
}

fn load_safe_linear(pos: vec2<i32>, max_coord: vec2<i32>) -> vec4<f32> {
    let clamped = clamp(pos, vec2<i32>(0), max_coord);
    return to_linear_exact(totalLoad(clamped));
}

fn catmull_rom_slow_unrolled(
    start_i: vec2<i32>,
    max_coord: vec2<i32>,
    wx: array<f32, 4>,
    wy: array<f32, 4>
) -> vec4<f32> {
    let r0 = load_safe_linear(vec2<i32>(start_i.x,     start_i.y), max_coord) * wx[0]
           + load_safe_linear(vec2<i32>(start_i.x + 1, start_i.y), max_coord) * wx[1]
           + load_safe_linear(vec2<i32>(start_i.x + 2, start_i.y), max_coord) * wx[2]
           + load_safe_linear(vec2<i32>(start_i.x + 3, start_i.y), max_coord) * wx[3];
    let r1 = load_safe_linear(vec2<i32>(start_i.x,     start_i.y + 1), max_coord) * wx[0]
           + load_safe_linear(vec2<i32>(start_i.x + 1, start_i.y + 1), max_coord) * wx[1]
           + load_safe_linear(vec2<i32>(start_i.x + 2, start_i.y + 1), max_coord) * wx[2]
           + load_safe_linear(vec2<i32>(start_i.x + 3, start_i.y + 1), max_coord) * wx[3];
    let r2 = load_safe_linear(vec2<i32>(start_i.x,     start_i.y + 2), max_coord) * wx[0]
           + load_safe_linear(vec2<i32>(start_i.x + 1, start_i.y + 2), max_coord) * wx[1]
           + load_safe_linear(vec2<i32>(start_i.x + 2, start_i.y + 2), max_coord) * wx[2]
           + load_safe_linear(vec2<i32>(start_i.x + 3, start_i.y + 2), max_coord) * wx[3];
    let r3 = load_safe_linear(vec2<i32>(start_i.x,     start_i.y + 3), max_coord) * wx[0]
           + load_safe_linear(vec2<i32>(start_i.x + 1, start_i.y + 3), max_coord) * wx[1]
           + load_safe_linear(vec2<i32>(start_i.x + 2, start_i.y + 3), max_coord) * wx[2]
           + load_safe_linear(vec2<i32>(start_i.x + 3, start_i.y + 3), max_coord) * wx[3];
    return r0 * wy[0] + r1 * wy[1] + r2 * wy[2] + r3 * wy[3];
}

fn catmull_rom_magnify(src_pos: vec2<f32>) -> vec4<f32> {
    let tex_size_u = contentSize();
    let pixel_coord = src_pos - 0.5;
    let base_coord = vec2<i32>(floor(pixel_coord));
    let f = fract(pixel_coord);

    let wx = catmull_rom_weights(f.x);
    let wy = catmull_rom_weights(f.y);
    let max_coord = vec2<i32>(tex_size_u) - 1;

    let ts = i32(transform.tile_size);

    let start_i = base_coord - vec2<i32>(1);
    let end_i   = base_coord + vec2<i32>(2);

    let canvas_in_bounds = start_i.x >= 0 && start_i.y >= 0 && end_i.x <= max_coord.x && end_i.y <= max_coord.y;
    let tile_TL = start_i / ts;
    let tile_BR = end_i / ts;
    let is_single_tile = all(tile_TL == tile_BR) && canvas_in_bounds;

    var final_color_linear = vec4<f32>(0.0);

    if (is_single_tile) {
        let idx = tile_TL.y * 2 + tile_TL.x;
        let local_offset = -tile_TL * ts;
        let p_start = start_i + local_offset;

        if (idx == 0) {
            final_color_linear = catmull_rom_fast_unrolled(src_tex0, p_start, wx, wy);
        } else if (idx == 1) {
            final_color_linear = catmull_rom_fast_unrolled(src_tex1, p_start, wx, wy);
        } else if (idx == 2) {
            final_color_linear = catmull_rom_fast_unrolled(src_tex2, p_start, wx, wy);
        } else {
            final_color_linear = catmull_rom_fast_unrolled(src_tex3, p_start, wx, wy);
        }
    } else {
        final_color_linear = catmull_rom_slow_unrolled(start_i, max_coord, wx, wy);
    }

    // Colour unclamped: an HDR tile's is legitimately outside [0,1].
    let srgb = to_srgb_exact(final_color_linear);
    return vec4<f32>(srgb.rgb, clamp(srgb.a, 0.0, 1.0));
}

fn catmull_rom_kernel(x: f32) -> f32 {
    let a = abs(x);
    if (a < 1.0) {
        return 1.5 * a * a * a - 2.5 * a * a + 1.0;
    }
    if (a < 2.0) {
        return -0.5 * a * a * a + 2.5 * a * a - 4.0 * a + 2.0;
    }
    return 0.0;
}

// Stretched over a footprint wider than a texel, so shrinking low-passes instead of aliasing.
fn catmull_rom_scaled(center: vec2<f32>, scale: vec2<f32>) -> vec4<f32> {
    let s = max(scale, vec2<f32>(1.0));
    let max_coord = vec2<i32>(contentSize()) - 1;
    // Texel i, centred at i + 0.5, is in reach within two scaled texels.
    let first = vec2<i32>(ceil(center - 0.5 - 2.0 * s));
    let last = vec2<i32>(floor(center - 0.5 + 2.0 * s));

    var sum = vec4<f32>(0.0);
    var total = 0.0;
    for (var y: i32 = first.y; y <= last.y; y++) {
        let wy = catmull_rom_kernel((f32(y) + 0.5 - center.y) / s.y);
        for (var x: i32 = first.x; x <= last.x; x++) {
            let w = wy * catmull_rom_kernel((f32(x) + 0.5 - center.x) / s.x);
            sum += load_safe_linear(vec2<i32>(x, y), max_coord) * w;
            total += w;
        }
    }
    return sum / max(total, 0.0001);
}

fn resolve(src_start: vec2<f32>, scale: vec2<f32>) -> vec4<f32> {
    let center = src_start + 0.5 * scale;
    if (max(scale.x, scale.y) <= 1.0) {
        return catmull_rom_magnify(center);
    }
    let srgb = to_srgb_exact(catmull_rom_scaled(center, scale));
    return vec4<f32>(srgb.rgb, clamp(srgb.a, 0.0, 1.0));
}
