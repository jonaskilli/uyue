/**
 * 场景特效决策层：语义标注 JSON → 渲染轨道 tracks
 *
 * 这是站点主包 index-BLGBg1UO.js 中以下函数的等价可读重写：
 *   fF / MA / wj   → config.cueMap（mapping/semantic-cues.json）
 *   FB / dc        → config.semanticEffects（presets/semantic-effects.json）
 *   v$ / i3 / fue  → config.presets（presets/effects.json）
 *   Zde            → zde()
 *   pue / Iw       → normalize() / validate()
 *   Gde            → buildEffects()
 *   hue / n5e      → findWeather() / effectsForAction()
 *   dJ             → allowedEffectIds()
 *
 * 不依赖任何框架，可直接移植到 Node / 浏览器 / 后端。
 */

export interface Track {
  kind: string;
  variant?: string;
  at: number;
  duration: number;
  [key: string]: unknown;
}

export interface ActionPoint { 0: number; 1: number; length: 2 }

export interface Cue {
  kind: string;
  intensity?: 'mild' | 'strong' | 'extreme';
  windLevel?: number;
  color?: string;
  opacity?: number;
  cId?: string;
}

export interface ActionAnnotation {
  cs: number;
  o: number;
  cues?: Cue[];
  tone?: 'lighthearted' | 'serious' | 'neutral';
  fx?: EffectRef[];
}

export interface EffectRef {
  id: string;
  color?: string;
  opacity?: number;
  cId?: string;
  windLevel?: number;
}

export interface WeatherV2 {
  s: ActionPoint;
  e: ActionPoint;
  weather: 'rain' | 'snow';
  intensity: 'light' | 'moderate' | 'heavy' | 'violent';
  lightning?: boolean;
  wind?: boolean;
  indoors: boolean;
}

export interface WeatherV1 {
  s: ActionPoint;
  e: ActionPoint;
  t: 'r' | 's';
  lv: 1 | 2 | 3 | 4;
  l: 0 | 1;
  w: 0 | 1;
  indoors: 0 | 1;
}

export interface Annotation {
  v?: 1 | 2;
  a: ActionAnnotation[];
  w: Array<WeatherV1 | WeatherV2>;
  op?: { id: string; cId?: string };
  overrides?: { a?: ActionAnnotation[]; w?: WeatherV1[] };
}

export interface StoryAction {
  chapterSeq: number;
  order: number;
  fromLine?: number;
  toLine?: number;
  characterId?: string;
  targetIds?: string[];
  characterIds?: string[];
}

export interface SemanticEffect {
  id: string;
  name: string;
  preset: string;
  groups: string[];
  guide?: string;
  hold?: boolean;
  target?: boolean;
  renderer?: string;
  tracks?: Track[];
}

export interface Preset {
  id: string;
  name: string;
  category: string;
  duration: number;
  tracks: Track[];
}

export interface ResolverConfig {
  presets: Preset[];
  semanticEffects: SemanticEffect[];
  cueMap: {
    cueToEffectIds: Record<string, string[]>;
    emotionToEffectId: Record<string, string>;
    cuePriority: Record<string, number>;
    intensitySpecialRules: Record<string, Record<string, string[]>>;
    llmAllowedEffectFilter: {
      excludeTargetRequired: boolean;
      excludeRendererPortrait: boolean;
      excludeIds: string[];
      excludeTrackKinds: string[];
    };
    idNormalization?: { removedIds: string[] };
  };
}

const isWeatherV2 = (w: WeatherV1 | WeatherV2): w is WeatherV2 =>
  'weather' in w && 'intensity' in w;

const pointKey = (p: ActionPoint) => `${p[0]}:${p[1]}`;

/** 比较两个动作点：a < b 返回负数 */
const cmpPoint = (a: ActionPoint, b: ActionPoint) =>
  a[0] - b[0] || a[1] - b[1];

const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

/** e1 → e01，a2 → a02，s8 → s08 */
function normalizeId(id: string): string {
  return id.replace(/^([eas])([1-9])$/, (_m, a, b) => `${a}0${b}`);
}

/** 稳定序列化去重（站点 pue 里的 n/r 两步） */
function dedupe<T>(list: T[], keyOf: (x: T) => string): T[] {
  const seen = new Set<string>();
  return list.filter(x => {
    const k = keyOf(x);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

const stableKey = (o: unknown) => JSON.stringify(o, (_k, v) =>
  v && typeof v === 'object' && !Array.isArray(v)
    ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b)))
    : v
);

/** Zde：v2 天气对象 → v1 规范区间；非法直接丢弃 */
export function zde(w: WeatherV2 | WeatherV1): WeatherV1 | null {
  if (!isWeatherV2(w)) return w;
  if (!['rain', 'snow'].includes(w.weather) || typeof w.indoors !== 'boolean') return null;
  const lvMap = { light: 1, moderate: 2, heavy: 3, violent: 4 } as const;
  const lv = lvMap[w.intensity];
  if (!lv) return null;
  return {
    s: w.s,
    e: w.e,
    t: w.weather === 'rain' ? 'r' : 's',
    lv,
    l: w.lightning ? 1 : 0,
    w: w.wind ? 1 : 0,
    indoors: w.indoors ? 1 : 0,
  };
}

/** pue + Iw：规范化、去重、区间冲突处理、结构校验 */
export function normalize(raw: Annotation): Annotation {
  // 站点 pue() 的过滤名单；需要可配置时可改为从 config.cueMap.idNormalization 读取
  const removedIds = new Set(['a12', 'a13', 'e23']);
  const fxOf = (fx: EffectRef[]): EffectRef[] =>
    dedupe(
      fx.filter(f => f && typeof f.id === 'string' && !removedIds.has(normalizeId(f.id)))
        .map(f => ({ ...f, id: normalizeId(f.id) })),
      stableKey
    );

  const a = dedupe(
    (raw.a ?? []).map(x => ({
      ...x,
      cues: dedupe(x.cues ?? [], stableKey),
      fx: x.fx ? fxOf(x.fx) : undefined,
    })),
    x => `${x.cs}:${x.o}`
  );

  const w = dedupe(
    (raw.w ?? []).map(zde).filter((x): x is WeatherV1 => x !== null)
      .map(x => ({
        ...x,
        lv: Math.min(4, Math.max(1, Number(x.lv))) as 1 | 2 | 3 | 4,
        l: Number(Boolean(x.l)) as 0 | 1,
        w: Number(Boolean(x.w)) as 0 | 1,
        indoors: Number(Boolean(x.indoors)) as 0 | 1,
      })),
    stableKey
  );

  // 区间冲突：类型不同的重叠部分留空，保留非冲突部分
  const merged: WeatherV1[] = [];
  for (const cur of w) {
    let conflict = false;
    for (const prev of merged) {
      const overlap = cmpPoint(cur.s, prev.e) <= 0 && cmpPoint(cur.e, prev.s) >= 0;
      if (overlap && (prev.t !== cur.t || prev.lv !== cur.lv)) {
        conflict = true;
        break;
      }
      if (overlap && pointKey(prev.e) === pointKey(cur.s)) {
        prev.e = cur.e; // 同类型相邻区间合并
        conflict = true;
        break;
      }
    }
    if (!conflict) merged.push(cur);
  }

  if (!Array.isArray(a) || !Array.isArray(merged)) {
    throw new Error('特效结果须包含 a、w 数组');
  }
  return { ...raw, a, w: merged };
}

/** dc：语义效果目录查找；e21 是 e01 的别名 */
function dc(config: ResolverConfig, id: string): SemanticEffect | undefined {
  const key = id === 'e21' ? 'e01' : normalizeId(id);
  return config.semanticEffects.find(x => x.id === key);
}

/** i3：效果 ID → tracks（语义效果自带 tracks 优先，否则查预设表） */
export function i3(config: ResolverConfig, ref: EffectRef): Track[] {
  const effect = dc(config, ref.id);
  const fromPreset = config.presets.find(p => p.id === effect?.preset);
  const tracks = clone(effect?.tracks ?? fromPreset?.tracks ?? []);
  if (ref.id === 'a18') {
    for (const t of tracks) {
      if (t.kind === 'overlay' && t.variant === 'tint') {
        if (ref.color && /^#[0-9a-fA-F]{6}$/.test(ref.color)) t.color = ref.color;
        t.strength = Math.max(0, Math.min(1, ref.opacity ?? 0.2));
      }
    }
  }
  return tracks;
}

/** dJ：开放给大模型自动选择的效果 ID */
export function allowedEffectIds(config: ResolverConfig): string[] {
  const f = config.cueMap.llmAllowedEffectFilter;
  return config.semanticEffects
    .filter(e => !f.excludeTargetRequired || !e.target)
    .filter(e => !f.excludeRendererPortrait || e.renderer !== 'portrait')
    .filter(e => !f.excludeIds.includes(e.id))
    .filter(e => i3(config, { id: e.id }).length > 0)
    .filter(e => i3(config, { id: e.id }).every(t => !f.excludeTrackKinds.includes(t.kind)))
    .map(e => e.id);
}

/** 找覆盖某个动作的天气区间（站点 hue，默认排除室内） */
export function findWeather(annotation: Annotation, action: StoryAction, includeIndoor = false): WeatherV1 | undefined {
  const p: ActionPoint = [action.chapterSeq, action.order] as ActionPoint;
  return annotation.w.find(w =>
    (includeIndoor || w.indoors !== 1) &&
    cmpPoint(w.s, p) <= 0 && cmpPoint(w.e, p) >= 0
  ) as WeatherV1 | undefined;
}

/** Gde：语义标注 + 动作列表 → 每个动作最终的效果 ID 列表 */
export function buildEffects(config: ResolverConfig, annotation: Annotation, actions: StoryAction[]): Annotation {
  const cueMap = config.cueMap;
  const byAction = new Map(annotation.a.map(a => [`${a.cs}:${a.o}`, a]));
  const outA = actions.flatMap(action => {
    const ann = byAction.get(`${action.chapterSeq}:${action.order}`);
    const weather = findWeather(annotation, action);
    const usedGroups = new Set<string>();
    const fx: EffectRef[] = [];

    const push = (id: string, cue?: Cue) => {
      const effect = dc(config, id);
      if (!effect) return;
      if (effect.groups.some(g => usedGroups.has(g))) return;
      effect.groups.forEach(g => usedGroups.add(g));
      const ref: EffectRef = { id: effect.id };
      if (effect.id === 'a18') {
        ref.color = cue?.color;
        ref.opacity = cue?.opacity ?? 0.2;
      }
      if (effect.target && cue?.cId) ref.cId = cue.cId;
      if (effect.id === 'a17') ref.windLevel = cue?.windLevel ?? 1;
      fx.push(ref);
    };

    // 天气闪电预先占用 lightning group，避免重复
    if (weather && weather.indoors === 0 && weather.l) usedGroups.add('lightning');

    // cues 按优先级降序
    const cues = [...(ann?.cues ?? [])].sort((a, b) =>
      (cueMap.cuePriority[b.kind] ?? 0) - (cueMap.cuePriority[a.kind] ?? 0) ||
      a.kind.localeCompare(b.kind) ||
      (a.cId ?? '').localeCompare(b.cId ?? '')
    );

    for (const cue of cues) {
      let ids = cueMap.cueToEffectIds[cue.kind] ?? [];
      const special = cueMap.intensitySpecialRules[cue.kind];
      if (special) {
        if (cue.kind === 'shock') {
          ids = special[cue.intensity ?? 'mild'] ?? special['strong'] ?? [];
        } else if (cue.intensity && cue.intensity !== 'mild') {
          ids = special['not_mild'] ?? ids;
        }
      }
      for (const id of ids) push(id, cue);
    }

    // 轻松氛围且没有 camera/impact 时补情绪符号
    if (ann?.tone === 'lighthearted' && !usedGroups.has('camera') && !usedGroups.has('impact')) {
      for (const cue of cues) {
        const id = cueMap.emotionToEffectId[cue.kind];
        if (id) push(id, cue);
      }
    }

    return fx.length ? [{ cs: action.chapterSeq, o: action.order, fx }] : [];
  });

  return { ...annotation, a: outA };
}

/** 完整解析入口：标注 → 规范化 → 效果 ID → tracks */
export function createEffectResolver(config: ResolverConfig) {
  return {
    config,
    allowedEffectIds: () => allowedEffectIds(config),
    parse(raw: Annotation, actions: StoryAction[]) {
      const validated = normalize(raw);
      const built = buildEffects(config, validated, actions);
      const final = built.overrides
        ? {
            ...built,
            a: [
              ...built.a.filter(x => !(built.overrides!.a ?? []).some(o => o.cs === x.cs && o.o === x.o)),
              ...(built.overrides!.a ?? []),
            ],
            w: built.overrides!.w ?? built.w,
          }
        : built;
      return final;
    },
    /** 某个动作此刻应该渲染哪些轨道 */
    tracksForAction(annotation: Annotation, action: StoryAction): Track[] {
      const tracks: Track[] = [];
      for (const a of annotation.a) {
        if (a.cs === action.chapterSeq && a.o === action.order) {
          for (const ref of a.fx ?? []) tracks.push(...i3(config, ref));
        }
      }
      return tracks;
    },
  };
}
