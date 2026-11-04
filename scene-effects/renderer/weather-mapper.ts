/**
 * 天气区间 → 粒子渲染轨道
 *
 * 对应站点 MusicCatalogPicker-tesK0yHr.js 中 q() 的这段：
 *   if (W && W.indoors !== 1) {
 *     const X = W.t === "r";
 *     const ht = Ko.find(b => b.id === (X ? "rain" : "snow")).tracks.find(t => t.kind === "particles");
 *     S = { ...ht, duration: Infinity,
 *           count: (X ? [90,180,340,600] : [60,110,230,400])[W.lv-1],
 *           fallSpeed: [.6,1,1.4,1.9][W.lv-1],
 *           wind: W.w ? 1 : 0 };
 *     _.populate(u, c, S, 1);
 *   }
 */

import type { Track, WeatherV1, Preset } from './effect-resolver';

export interface WeatherMapping {
  particleCountByLevel: { rain: number[]; snow: number[] };
  fallSpeedByLevel: number[];
  presetLookup: { rain: string; snow: string; lightning: string };
}

const RAIN_COUNTS = [90, 180, 340, 600];
const SNOW_COUNTS = [60, 110, 230, 400];
const FALL_SPEEDS = [0.6, 1.0, 1.4, 1.9];

/** 取预设里的 particles 轨道作为模板 */
function baseParticlesTrack(presets: Preset[], presetId: string): Track | undefined {
  return presets.find(p => p.id === presetId)?.tracks.find(t => t.kind === 'particles');
}

/**
 * 天气 → 持续型 particles 轨道。
 * 返回 null 表示不生成降水（室内 / 无天气 / 非法等级）。
 * duration 用 Number.POSITIVE_INFINITY 表达「持续到区间结束」；
 * 需要 JSON 序列化时用 null 或极大值替代。
 */
export function weatherToParticleTrack(
  w: WeatherV1 | undefined,
  presets: Preset[],
  mapping?: WeatherMapping
): Track | null {
  if (!w || w.indoors === 1) return null;
  const lv = Math.min(4, Math.max(1, Number(w.lv)));
  const isRain = w.t === 'r';
  const counts = mapping?.particleCountByLevel?.[isRain ? 'rain' : 'snow'] ??
    (isRain ? RAIN_COUNTS : SNOW_COUNTS);
  const fallSpeed = mapping?.fallSpeedByLevel?.[lv - 1] ?? FALL_SPEEDS[lv - 1];
  const presetId = mapping?.presetLookup?.[isRain ? 'rain' : 'snow'] ?? (isRain ? 'rain' : 'snow');
  const base = baseParticlesTrack(presets, presetId) ?? {
    kind: 'particles',
    variant: isRain ? 'rain' : 'snow',
    at: 0,
    duration: 7,
  };

  return {
    ...base,
    kind: 'particles',
    variant: isRain ? 'rain' : 'snow',
    at: 0,
    duration: Number.POSITIVE_INFINITY,
    count: counts[lv - 1],
    fallSpeed,
    wind: w.w ? 1 : 0,
    size: (base.size as number | undefined) ?? 1,
    opacity: (base.opacity as number | undefined) ?? 0.9,
  };
}

/** 闪电：天气区间带 l=1 时叠加 lightning overlay（reduced-motion 下由调用方跳过） */
export function lightningTrack(w: WeatherV1 | undefined, presets: Preset[]): Track | null {
  if (!w || !w.l || w.indoors === 1) return null;
  const preset = presets.find(p => p.id === 'lightning');
  const base = preset?.tracks.find(t => t.kind === 'overlay') ?? {
    kind: 'overlay', variant: 'lightning', strength: 0.75, at: 0, duration: 8,
  };
  return { ...base };
}

/** 一次性拿到某动作当前需要的全部天气轨道 */
export function weatherTracks(
  w: WeatherV1 | undefined,
  presets: Preset[],
  mapping?: WeatherMapping,
  reducedMotion = false
): Track[] {
  const out: Track[] = [];
  const particles = weatherToParticleTrack(w, presets, mapping);
  if (particles) out.push(particles);
  if (!reducedMotion) {
    const lightning = lightningTrack(w, presets);
    if (lightning) out.push(lightning);
  }
  return out;
}

/** 天气区间是否变化：只有变化时才重建粒子池（避免每帧 new 对象） */
export function weatherKey(w: WeatherV1 | undefined): string {
  if (!w) return '';
  return JSON.stringify([w.t, w.lv, w.l, w.w, w.indoors]);
}
