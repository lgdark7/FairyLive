import numpy as np
from PIL import Image, ImageFilter
import subprocess
import os
import sys
import time

def main():
    print("Starting Fairy Live 9-Second Seamless Logo Animation Generator...")
    start_time = time.time()

    W, H = 1280, 720
    fps = 30
    total_frames = 270 # 9 seconds * 30 fps

    # Aligned Canonical Panels from earlier calibration:
    can_dir = "C:/Users/NOTHING/canonical_panels"
    can_files = [os.path.join(can_dir, f"can_{i}.png") for i in range(10)]
    panels = [np.array(Image.open(f).convert('RGB')).astype(np.float32) for f in can_files]

    # Load high-res logo placed on canonical canvas
    highres_im = Image.open(os.path.join(can_dir, "can_highres_logo.png")).convert('RGB')
    highres_arr = np.array(highres_im).astype(np.float32)

    # Ensure pure black background for all panels: pixels < 10 become 0
    for i in range(len(panels)):
        panels[i][panels[i] < 8.0] = 0.0
    highres_arr[highres_arr < 8.0] = 0.0

    # Ensure Panel 9 is cleanly blended with highres_arr for ultra-crisp chrome finish
    panels[9] = np.maximum(panels[9], highres_arr)

    # Output MP4 configuration
    output_mp4 = "fairy_live_splash_intro.mp4"
    cmd = [
        'ffmpeg', '-y', '-f', 'rawvideo', '-vcodec', 'rawvideo',
        '-s', f'{W}x{H}', '-pix_fmt', 'rgb24', '-r', str(fps),
        '-i', '-', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
        '-preset', 'fast', '-crf', '17', output_mp4
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    sparkle_x, sparkle_y = 681, 140

    def get_light_pen(x, y, radius=8, brightness=255.0):
        # Gaussian glow
        y_grid, x_grid = np.ogrid[-radius*2:radius*2+1, -radius*2:radius*2+1]
        dist_sq = x_grid**2 + y_grid**2
        glow = brightness * np.exp(-dist_sq / (2 * (radius/1.8)**2))
        return glow, radius*2

    for frame_idx in range(total_frames):
        t = frame_idx / float(fps) # 0.0 to 8.966 seconds
        frame = np.zeros((H, W, 3), np.float32)

        if t < 1.0:
            # 0.0 to 1.0s: Ignition & Arc drawing (Black -> Panel 0 -> Panel 1)
            # 0.0 to 0.3s: Black to spark ignition
            # 0.3 to 1.0s: Arc stroke sweeping towards sparkle
            if t < 0.3:
                p = t / 0.3
                # Spark ignition fade in
                frame = panels[0] * (p ** 1.5)
            else:
                p = (t - 0.3) / 0.7
                # Morph Panel 0 -> Panel 1 with drawing sweep along arc
                # In Panel 1, the arc sweeps from around x=717, y=175 up to x=681, y=140 and around
                arc_diff = np.maximum(0, panels[1] - panels[0])
                # Progressive reveal based on angle
                angles = np.arctan2(np.arange(H)[:, None] - 360, np.arange(W)[None, :] - 640)
                # Reveal arc
                frame = panels[0] + arc_diff * np.clip(p * 1.2, 0, 1)

                # Add glowing light pen tip moving along the arc
                pen_angle = -0.6 + p * 1.4 # sweeps along arc
                pen_r = 190.0
                pen_x = int(640 + pen_r * np.cos(pen_angle))
                pen_y = int(360 - pen_r * np.sin(pen_angle))
                if 0 <= pen_x < W and 0 <= pen_y < H:
                    # draw small white glow
                    x0, x1 = max(0, pen_x - 12), min(W, pen_x + 13)
                    y0, y1 = max(0, pen_y - 12), min(H, pen_y + 13)
                    gx = np.arange(x0, x1) - pen_x
                    gy = np.arange(y0, y1) - pen_y
                    d2 = gx[None, :]**2 + gy[:, None]**2
                    pen_glow = 255.0 * np.exp(-d2 / 24.0)[:, :, None]
                    frame[y0:y1, x0:x1] = np.maximum(frame[y0:y1, x0:x1], pen_glow)

        elif t < 2.0:
            # 1.0 to 2.0s: Fairy silhouette begins to form (head, hair, body)
            p = (t - 1.0) / 1.0
            diff = np.maximum(0, panels[2] - panels[1])
            # Progressive reveal top-to-bottom
            ys = np.arange(H)[:, None]
            min_y, max_y = 140, 480
            cutoff = min_y + p * (max_y - min_y)
            mask = np.clip((cutoff - ys) / 25.0, 0.0, 1.0)[:, :, None]
            frame = panels[1] + diff * mask

            # Drawing pen tip at bottom edge of current reveal
            pen_y = int(cutoff)
            pen_x = int(640 - (cutoff - 140) * 0.1)
            if 0 <= pen_x < W and 0 <= pen_y < H and p < 0.95:
                x0, x1 = max(0, pen_x - 10), min(W, pen_x + 11)
                y0, y1 = max(0, pen_y - 10), min(H, pen_y + 11)
                gx = np.arange(x0, x1) - pen_x
                gy = np.arange(y0, y1) - pen_y
                d2 = gx[None, :]**2 + gy[:, None]**2
                pen_glow = 220.0 * np.exp(-d2 / 18.0)[:, :, None]
                frame[y0:y1, x0:x1] = np.maximum(frame[y0:y1, x0:x1], pen_glow)

        elif t < 3.0:
            # 2.0 to 3.0s: Left wings begin to draw
            p = (t - 2.0) / 1.0
            diff = np.maximum(0, panels[3] - panels[2])
            # Wings radiate outward from spine (x ~ 620 towards left x ~ 400)
            xs = np.arange(W)[None, :]
            cutoff_x = 640 - p * 240
            mask = np.clip((650 - xs) / (650 - cutoff_x + 1e-4), 0.0, 1.0)
            mask = np.clip(mask, 0.0, 1.0)[:, :, None]
            frame = panels[2] + diff * (p * 0.4 + mask * 0.6)

            # Sparkle on wing tip
            tip_x = int(cutoff_x)
            tip_y = int(240 - p * 60)
            if 0 <= tip_x < W and 0 <= tip_y < H and p < 0.95:
                x0, x1 = max(0, tip_x - 10), min(W, tip_x + 11)
                y0, y1 = max(0, tip_y - 10), min(H, tip_y + 11)
                gx = np.arange(x0, x1) - tip_x
                gy = np.arange(y0, y1) - tip_y
                d2 = gx[None, :]**2 + gy[:, None]**2
                pen_glow = 220.0 * np.exp(-d2 / 20.0)[:, :, None]
                frame[y0:y1, x0:x1] = np.maximum(frame[y0:y1, x0:x1], pen_glow)

        elif t < 4.0:
            # 3.0 to 4.0s: Opposite wing & lower curves complete wireframe
            p = (t - 3.0) / 1.0
            diff = np.maximum(0, panels[4] - panels[3])
            frame = panels[3] + diff * (p ** 0.8)

        elif t < 5.0:
            # 4.0 to 5.0s: Wireframe to volumetric crystallization (Panel 4 -> Panel 5)
            p = (t - 4.0) / 1.0
            diff = np.maximum(0, panels[5] - panels[4])
            # Subtle pulse glow on apexes
            glow_intensity = 1.0 + 0.3 * np.sin(p * np.pi)
            frame = panels[4] * glow_intensity + diff * (p ** 1.2)

        elif t < 6.5:
            # 5.0 to 6.5s: Metallic reflection sweep (Panels 5 -> 6 -> 7 -> 8)
            p = (t - 5.0) / 1.5
            # Sub-stage interpolation
            if p < 0.33:
                sub_p = p / 0.33
                base = panels[5] * (1.0 - sub_p) + panels[6] * sub_p
            elif p < 0.66:
                sub_p = (p - 0.33) / 0.33
                base = panels[6] * (1.0 - sub_p) + panels[7] * sub_p
            else:
                sub_p = (p - 0.66) / 0.34
                base = panels[7] * (1.0 - sub_p) + panels[8] * sub_p

            # Specular light sweep across logo (diagonal line x + y)
            sweep_center = 400 + p * 800
            xs = np.arange(W)[None, :]
            ys = np.arange(H)[:, None]
            diag = xs * 0.7 + ys * 0.7
            spec_wave = np.exp(-((diag - sweep_center) / 60.0)**2)[:, :, None]
            spec_mask = (base.mean(axis=2, keepdims=True) > 20.0).astype(np.float32)

            frame = base + spec_wave * spec_mask * 120.0

        elif t < 7.5:
            # 6.5 to 7.5s: Centered chrome logo resting state with subtle shimmer
            p = (t - 6.5) / 1.0
            base = panels[8] * (1.0 - p) + panels[9] * p
            # Subtle breathing shimmer
            shimmer = 1.0 + 0.04 * np.sin(p * 2 * np.pi)
            # Soft twinkle on the four-pointed sparkle
            twinkle = 1.0 + 0.15 * np.sin(p * 4 * np.pi)
            frame = base * shimmer

            # Sparkle twinkle boost
            sx0, sx1 = max(0, sparkle_x - 20), min(W, sparkle_x + 21)
            sy0, sy1 = max(0, sparkle_y - 20), min(H, sparkle_y + 21)
            gx = np.arange(sx0, sx1) - sparkle_x
            gy = np.arange(sy0, sy1) - sparkle_y
            d2 = gx[None, :]**2 + gy[:, None]**2
            s_glow = 80.0 * (twinkle - 1.0) * np.exp(-d2 / 50.0)[:, :, None]
            frame[sy0:sy1, sx0:sx1] = np.clip(frame[sy0:sy1, sx0:sx1] + s_glow, 0, 255)

        else:
            # 7.5 to 9.0s: Reverse dissolution back to pure black
            # 7.5 to 8.2s: Chrome -> Wireframe (Panels 9 -> 4)
            # 8.2 to 8.7s: Wireframe -> Arc (Panels 4 -> 1)
            # 8.7 to 9.0s: Arc -> Spark -> Pure Black (Panel 1 -> 0 -> Black)
            rev_p = (t - 7.5) / 1.5 # 0.0 to 1.0
            if rev_p < 0.45:
                # Chrome to wireframe
                sub = rev_p / 0.45
                frame = panels[9] * (1.0 - sub) + panels[4] * sub
            elif rev_p < 0.80:
                # Wireframe contracting
                sub = (rev_p - 0.45) / 0.35
                frame = panels[4] * (1.0 - sub) + panels[1] * sub
            else:
                # Arc contracting and dissolving to black
                sub = (rev_p - 0.80) / 0.20
                frame = panels[1] * ((1.0 - sub) ** 2.0)
                # At the very end (frame 269), force pure black
                if frame_idx >= total_frames - 2:
                    frame = np.zeros_like(frame)

        frame = np.clip(frame, 0, 255).astype(np.uint8)
        proc.stdin.write(frame.tobytes())

        if frame_idx % 30 == 0:
            print(f"Rendered frame {frame_idx}/{total_frames} (t={t:.2f}s)")

    proc.stdin.close()
    proc.wait()

    elapsed = time.time() - start_time
    print(f"Animation rendering complete in {elapsed:.2f} seconds!")
    print(f"Saved: {output_mp4} (Return code: {proc.returncode})")

if __name__ == '__main__':
    main()
