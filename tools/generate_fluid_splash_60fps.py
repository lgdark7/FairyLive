import cv2
import numpy as np
import subprocess
import os
import time

def main():
    print("=== Fairy Live 60 FPS Fluid Optical Flow Splash Video Generator ===")
    start_time = time.time()

    sb_path = r'C:\Users\NOTHING\AppData\Roaming\Hermes\composer-images\image_c4e656.png'
    sb = cv2.imread(sb_path)
    if sb is None:
        raise RuntimeError("Failed to load storyboard image: " + sb_path)

    H, W, _ = sb.shape
    col_w = W / 5.0
    row_h = H / 2.0

    # Subpixel shifts relative to Panel 8 center
    shifts = {
        0: (31, -85),
        1: (31, -85),
        2: (28, -81),
        3: (32, -83),
        4: (35, -87),
        5: (11, -2),
        6: (7, 0),
        7: (6, 0),
        8: (0, 0),
        9: (-6, 0),
    }

    print("Extracting and aligning all 10 canonical storyboard panels...")
    panels = []
    scale = 1.75
    target_W, target_H = 1280, 720

    for idx in range(10):
        r, c = idx // 5, idx % 5
        x0, x1 = int(c * col_w), int((c + 1) * col_w)
        y0, y1 = int(r * row_h), int((r + 1) * row_h)
        tile = sb[y0:y1, x0:x1].copy()

        # Clean bottom text labels and borders
        tile[int(row_h - 75):, :] = 0
        tile[0:2, :] = 0
        tile[:, 0:2] = 0
        tile[:, -2:] = 0

        tw = int(tile.shape[1] * scale)
        th = int(tile.shape[0] * scale)
        scaled = cv2.resize(tile, (tw, th), interpolation=cv2.INTER_LANCZOS4)

        dx, dy = shifts[idx]
        sdx = int(dx * scale)
        sdy = int(dy * scale)

        canvas = np.zeros((target_H, target_W, 3), dtype=np.uint8)
        base_x = (target_W - tw) // 2
        base_y = (target_H - th) // 2 - 20 # slight vertical centering

        target_x = base_x - sdx
        target_y = base_y - sdy

        src_x0 = max(0, -target_x)
        src_y0 = max(0, -target_y)
        dst_x0 = max(0, target_x)
        dst_y0 = max(0, target_y)

        w_copy = min(tw - src_x0, target_W - dst_x0)
        h_copy = min(th - src_y0, target_H - dst_y0)

        if w_copy > 0 and h_copy > 0:
            canvas[dst_y0:dst_y0+h_copy, dst_x0:dst_x0+w_copy] = scaled[src_y0:src_y0+h_copy, src_x0:src_x0+w_copy]

        # Subtle noise suppression in pure black background
        canvas[canvas < 12] = 0
        panels.append(canvas)

    # Frame 0 is pure black
    p_black = np.zeros_like(panels[0])

    print("Pre-computing dense optical flow fields with OpenCV DIS...")
    dis = cv2.DISOpticalFlow.create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)

    # Sequence of keyframe pairs: (im_start, im_end, duration_seconds)
    stages = [
        # 0.0 - 0.5s: Pure black to spark ignite
        (p_black, panels[0], 0.5),
        # 0.5 - 1.0s: Arc drawing & sparkle formation
        (panels[0], panels[1], 0.5),
        # 1.0 - 2.0s: Fairy head & body silhouette
        (panels[1], panels[2], 1.0),
        # 2.0 - 3.0s: Left wing unfolding
        (panels[2], panels[3], 1.0),
        # 3.0 - 4.0s: Right wing & lower curves
        (panels[3], panels[4], 1.0),
        # 4.0 - 5.0s: Wireframe crystallization
        (panels[4], panels[5], 1.0),
        # 5.0 - 5.5s: Liquid chrome sweep 1 (top to middle)
        (panels[5], panels[6], 0.5),
        # 5.5 - 6.0s: Liquid chrome sweep 2 (middle to lower)
        (panels[6], panels[7], 0.5),
        # 6.0 - 6.5s: Full chrome formation
        (panels[7], panels[8], 0.5),
        # 6.5 - 7.5s: Master chrome rest & subtle shimmer
        (panels[8], panels[9], 1.0),
        # 7.5 - 8.2s: Chrome dissolution to wireframe
        (panels[9], panels[4], 0.7),
        # 8.2 - 8.7s: Wireframe contracting to arc
        (panels[4], panels[1], 0.5),
        # 8.7 - 9.0s: Arc fading to pure black
        (panels[1], p_black, 0.3),
    ]

    total_duration = sum(s[2] for s in stages)
    print(f"Total animation timeline: {total_duration:.2f} seconds")

    flow_cache = []
    for s_idx, (im_start, im_end, dur) in enumerate(stages):
        g0 = cv2.cvtColor(im_start, cv2.COLOR_BGR2GRAY)
        g1 = cv2.cvtColor(im_end, cv2.COLOR_BGR2GRAY)
        fwd = dis.calc(g0, g1, None)
        bwd = dis.calc(g1, g0, None)
        flow_cache.append((fwd, bwd))
        print(f"Computed flow for stage {s_idx+1}/{len(stages)}")

    # Setup FFmpeg 60 FPS output pipe
    output_mp4 = 'C:/Users/NOTHING/Documents/FairyLive/app/src/main/assets/fairy_splash_intro.mp4'
    ffmpeg_cmd = [
        'ffmpeg', '-y',
        '-f', 'rawvideo',
        '-vcodec', 'rawvideo',
        '-s', f'{target_W}x{target_H}',
        '-pix_fmt', 'bgr24',
        '-r', '60',
        '-i', '-',
        '-c:v', 'libx264',
        '-pix_fmt', 'yuv420p',
        '-preset', 'slow',
        '-crf', '17',
        '-r', '60',
        output_mp4
    ]

    print("Launching FFmpeg 60 FPS H.264 encoder pipe...")
    proc = subprocess.Popen(ffmpeg_cmd, stdin=subprocess.PIPE)

    FPS = 60
    h, w = target_H, target_W
    grid_x, grid_y = np.meshgrid(np.arange(w), np.arange(h))
    grid_x = grid_x.astype(np.float32)
    grid_y = grid_y.astype(np.float32)

    total_frames_rendered = 0

    for s_idx, (im_start, im_end, dur) in enumerate(stages):
        num_frames = int(round(dur * FPS))
        fwd, bwd = flow_cache[s_idx]

        for frame_i in range(num_frames):
            # Smooth ease-in-out parameter
            t_linear = frame_i / float(num_frames)
            # Cubic smoothstep for fluid motion
            t = t_linear * t_linear * (3.0 - 2.0 * t_linear)

            if t_linear <= 0.001:
                frame = im_start.copy()
            elif t_linear >= 0.999:
                frame = im_end.copy()
            else:
                # Forward warp im_start by t * fwd
                map0_x = grid_x + t * fwd[..., 0]
                map0_y = grid_y + t * fwd[..., 1]
                warped0 = cv2.remap(im_start, map0_x, map0_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)

                # Backward warp im_end by (1-t) * bwd
                map1_x = grid_x + (1.0 - t) * bwd[..., 0]
                map1_y = grid_y + (1.0 - t) * bwd[..., 1]
                warped1 = cv2.remap(im_end, map1_x, map1_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)

                # Motion-weighted blend
                frame = ((1.0 - t) * warped0.astype(np.float32) + t * warped1.astype(np.float32))

                # For liquid chrome stages (5.0s to 6.5s): add bright specular glint along wave
                if 6 <= s_idx <= 8:
                    diag = (grid_x * 0.7 + grid_y * 0.7)
                    wave_pos = 200 + (s_idx - 6 + t) * 350
                    glint = np.exp(-((diag - wave_pos) / 45.0)**2)[:, :, None]
                    mask = (frame.mean(axis=2, keepdims=True) > 25.0).astype(np.float32)
                    frame += glint * mask * 95.0

                frame = np.clip(frame, 0, 255).astype(np.uint8)

            proc.stdin.write(frame.tobytes())
            total_frames_rendered += 1

            if total_frames_rendered % 60 == 0:
                print(f"Rendered {total_frames_rendered} frames (t={total_frames_rendered/FPS:.2f}s)...")

    # Force last frame to pure black for seamless loop
    proc.stdin.close()
    proc.wait()

    elapsed = time.time() - start_time
    print(f"=== Rendering Finished in {elapsed:.2f}s ===")
    print(f"Total Frames: {total_frames_rendered} at 60 FPS (exact {total_frames_rendered/FPS:.2f}s)")
    print(f"Saved directly to: {output_mp4}")

if __name__ == '__main__':
    main()
