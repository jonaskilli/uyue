/**
 * Canvas 2D 粒子渲染器（可读等价实现）
 *
 * 站点用 PixiJS/WebGL + 离屏 canvas 生成纹理 + Sprite 池；
 * 这里改成不依赖任何库的 Canvas 2D 直绘，逻辑等价：
 *   - 确定性伪随机 hash(i, seed)，保证重绘/旋转屏幕不跳变
 *   - 位置取模回卷，粒子无限循环，不需要每帧 new 对象
 *   - 天气变化时由 weather-mapper 重建轨道，粒子池本身按轨道参数复用
 *   - duration = Infinity 表示持续区间，只做 0.5s 淡入，不做淡出
 *
 * 对应原始片段见 original/particle-renderer-snippets.js。
 */

import type { Track } from './effect-resolver';

/** 站点同款确定性伪随机：sin(i*127.1 + seed*311.7) * 43758.5453 % 1 */
export function hash(i: number, seed: number): number {
  const v = Math.sin(i * 127.1 + seed * 311.7) * 43758.5453;
  return v - Math.floor(v);
}

const wrap = (v: number, m: number) => ((v % m) + m) % m;

function fade(track: Track, t: number): number {
  const dur = track.duration ?? 7;
  const inFade = Math.min(1, t / 0.5);
  if (!Number.isFinite(dur)) return inFade;
  return Math.max(0, Math.min(inFade, (dur - t) / 0.5));
}

export interface RenderOptions {
  width: number;
  height: number;
  timeSec: number;
  reducedMotion?: boolean;
}

/**
 * 单轨道粒子渲染。
 * 实际项目里应由舞台循环遍历所有 tracks，逐条调用 drawTrack。
 */
export function drawTrack(ctx: CanvasRenderingContext2D, track: Track, opt: RenderOptions): void {
  const { width: w, height: h, timeSec: t } = opt;
  if (opt.reducedMotion && ['flash', 'eyelids'].includes(track.kind)) return;
  if (track.kind !== 'particles' && track.kind !== 'ambient') return;

  const count = Number(track.count ?? 100);
  const size = Number(track.size ?? 1);
  const opacity = Number(track.opacity ?? 0.9);
  const wind = Number(track.wind ?? 0);
  const fallSpeed = Number(track.fallSpeed ?? 1);
  const variant = String(track.variant ?? 'rain');
  const alpha = opacity * fade(track, t);

  ctx.save();
  ctx.globalAlpha = Math.max(0, Math.min(1, alpha));

  switch (variant) {
    case 'rain': {
      // 雨丝：细长线，按 atan2(wind, fallSpeed) 倾斜
      ctx.strokeStyle = '#cfe4f5';
      ctx.lineWidth = 1.1 * size;
      ctx.beginPath();
      for (let i = 0; i < count; i++) {
        const depth = 0.55 + hash(i, 7) * 0.9;       // 近景/远景
        const x = wrap(hash(i, 1) * w + t * wind * 80 * depth + Math.sin(t * 1.2 + i) * 6, w);
        const y = wrap(hash(i, 2) * h + t * fallSpeed * 190 * depth, h);
        const len = (10 + hash(i, 3) * 16) * size * depth;
        const tilt = Math.atan2(wind * 0.7, fallSpeed) * 0.55;
        ctx.moveTo(x, y);
        ctx.lineTo(x + Math.sin(tilt) * len, y + Math.cos(tilt) * len);
      }
      ctx.stroke();
      break;
    }
    case 'snow': {
      ctx.fillStyle = '#ffffff';
      for (let i = 0; i < count; i++) {
        const depth = 0.5 + hash(i, 11) * 1.0;
        const x = wrap(hash(i, 1) * w + t * wind * 26 * depth + Math.sin(t * 0.8 + i) * 18, w);
        const y = wrap(hash(i, 2) * h + t * fallSpeed * 55 * depth, h);
        const r = (0.9 + hash(i, 3) * 2.1) * size * depth;
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fill();
      }
      break;
    }
    case 'petals':
    case 'leaves':
    case 'butterflies': {
      // 花瓣 / 落叶 / 蝶：带旋转、横摆、正反面换色
      const isLeaf = variant === 'leaves';
      for (let i = 0; i < count; i++) {
        const p = hash(i, 5);
        const m = 0.45 + hash(i, 6) * 0.9;
        const drift = t * (isLeaf ? 33 : 22) * fallSpeed * m;
        const x = wrap(p * w + Math.sin(t * 0.9 + i) * 38 * m + Math.sin(t * 0.4 + i) * 18 * wind, w);
        const y = wrap(hash(i, 2) * h + drift, h);
        const angle = t * (isLeaf ? 0.8 : 0.45) * m + Math.sin(t * 1.1 + i) * 0.7;
        const flip = Math.cos(t * 1.6 + i);
        const scale = (0.18 + Math.abs(flip) * 0.82) * size * m;

        ctx.save();
        ctx.translate(x, y);
        ctx.rotate(angle);
        ctx.scale(scale, scale * (0.85 + 0.15 * Math.sin(t + i)));
        ctx.fillStyle = isLeaf
          ? (flip < 0 ? '#8a5a2b' : '#d98b3a')
          : (variant === 'butterflies' ? '#f4c2d7' : '#f2a6bd');
        ctx.beginPath();
        if (isLeaf) {
          ctx.moveTo(0, -10);
          ctx.quadraticCurveTo(9, -3, 0, 12);
          ctx.quadraticCurveTo(-9, -3, 0, -10);
        } else {
          ctx.moveTo(0, -9);
          ctx.bezierCurveTo(8, -8, 9, 4, 0, 10);
          ctx.bezierCurveTo(-9, 4, -8, -8, 0, -9);
        }
        ctx.fill();
        ctx.restore();
      }
      break;
    }
    case 'fireflies':
    case 'embers':
    case 'dust':
    case 'bubbles': {
      const rising = variant === 'embers' || variant === 'bubbles';
      const color = variant === 'fireflies' ? '#eaff8f'
        : variant === 'embers' ? '#ff8a3d'
        : variant === 'bubbles' ? '#bfe9f5' : '#c9b48f';
      ctx.fillStyle = color;
      ctx.strokeStyle = color;
      for (let i = 0; i < count; i++) {
        const phase = wrap(t / (2.3 + hash(i, 9) * 2.4) + hash(i, 12), 1);
        const y = rising ? h * (1.04 - phase * 0.98) : wrap(hash(i, 2) * h + t * 8, h);
        const x = wrap(hash(i, 1) * w + phase * 100 * wind + Math.sin(phase * 7 + i) * 14, w);
        const r = (1 + hash(i, 3) * 2.4) * size;
        const life = variant === 'dust' ? Math.sin(phase * Math.PI) ** 1.5 : 1;
        ctx.globalAlpha = Math.max(0, Math.min(1, alpha * life * (0.45 + 0.55 * Math.sin(t * 2 + i) ** 2)));
        if (variant === 'bubbles') {
          ctx.beginPath(); ctx.arc(x, y, r * 4, 0, Math.PI * 2); ctx.stroke();
        } else {
          ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
        }
      }
      break;
    }
    case 'fog':
    case 'smoke': {
      ctx.fillStyle = variant === 'fog' ? '#e6f0f0' : '#656b73';
      for (let i = 0; i < count; i++) {
        const x = wrap(hash(i, 1) * w + t * wind * 14 + Math.sin(t * 0.3 + i) * 20, w);
        const y = variant === 'smoke'
          ? wrap(h - t * 30 * (0.5 + hash(i, 4)), h)
          : h * (0.2 + hash(i, 2) * 0.7);
        const rx = (50 + hash(i, 3) * 70) * size;
        const ry = (16 + hash(i, 5) * 22) * size;
        ctx.globalAlpha = Math.max(0, Math.min(1, alpha * 0.035));
        for (let k = 5; k > 0; k--) {
          ctx.beginPath();
          ctx.ellipse(x, y, rx * k / 5, ry * k / 5, 0, 0, Math.PI * 2);
          ctx.fill();
        }
      }
      break;
    }
    case 'wind': {
      ctx.strokeStyle = '#e6f0f0';
      ctx.lineWidth = size;
      ctx.globalAlpha = Math.max(0, Math.min(1, alpha * 0.3));
      for (let i = 0; i < count; i++) {
        const x = wrap(hash(i, 1) * w + t * 120 * wind, w);
        const y = h * (0.1 + hash(i, 2) * 0.8);
        ctx.beginPath();
        ctx.moveTo(x, y);
        ctx.bezierCurveTo(x + 45, y - 13, x + 75, y + 12, x + 120 * size, y - 8);
        ctx.stroke();
      }
      break;
    }
    case 'rays': {
      ctx.fillStyle = '#fff3c4';
      ctx.globalAlpha = Math.max(0, Math.min(1, alpha * 0.035));
      for (let i = 0; i < count; i++) {
        const x = w * (0.2 + hash(i, 1) * 0.7);
        ctx.beginPath();
        ctx.moveTo(x, -20);
        ctx.lineTo(x + 40 * size, h);
        ctx.lineTo(x + 100 * size, h);
        ctx.lineTo(x + 60 * size, -20);
        ctx.closePath();
        ctx.fill();
      }
      break;
    }
    default:
      break;
  }
  ctx.restore();
}

/** 天气轨道与动作轨道一起绘制 */
export function drawTracks(
  ctx: CanvasRenderingContext2D,
  tracks: Track[],
  opt: RenderOptions
): void {
  ctx.clearRect(0, 0, opt.width, opt.height);
  for (const track of tracks) {
    const elapsed = opt.timeSec - Number(track.at ?? 0);
    if (elapsed < 0) continue;
    if (Number.isFinite(track.duration) && elapsed > Number(track.duration)) continue;
    drawTrack(ctx, track, { ...opt, timeSec: elapsed });
  }
}
