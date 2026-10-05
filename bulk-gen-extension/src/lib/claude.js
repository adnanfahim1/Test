// Claude = the "prompt engine". It turns your one idea into many different prompts.
// It never generates images or videos itself.
//
// Uses Anthropic's official JavaScript SDK. "dangerouslyAllowBrowser" just means
// "I know the key lives in this browser" - fine for a personal extension on your own PC.
// Your key is only sent to api.anthropic.com.

import Anthropic from '@anthropic-ai/sdk';

export const CLAUDE_MODELS = [
  { id: 'claude-opus-5-5', label: 'Claude Opus 5.5 (best quality)' },
  { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5 (faster, cheaper)' },
  { id: 'claude-haiku-4-5', label: 'Claude Haiku 4.5 (cheapest)' },
];

export const VARIATION_STYLES = {
  angle_lighting: 'Keep the same subject in every prompt. Vary the camera angle, framing, lighting, setting and time of day.',
  style_locked: 'Keep the subject AND the visual style locked. Vary only small details: pose, composition, background elements, props.',
  wild: 'Wild variations: reinterpret the idea freely (different settings, styles, eras, moods) while staying recognisably about the base idea.',
};

// How many prompts to ask for per Claude call. Smaller chunks are more reliable.
const CHUNK = 25;

// Opus 5.5 and Sonnet 5.5 support Anthropic's automatic "fallback" option: if Claude
// declines a request, the API retries it on another model instead of failing.
const FALLBACK_MODELS = new Set(['claude-opus-5-5', 'claude-sonnet-5-5']);

const OUTPUT_SCHEMA = {
  type: 'object',
  properties: {
    prompts: { type: 'array', items: { type: 'string' } },
  },
  required: ['prompts'],
  additionalProperties: false,
};

export function makeClient({ apiKey, baseURL }) {
  if (!apiKey) throw new Error('Add your Anthropic API key in Settings first.');
  return new Anthropic({
    apiKey,
    baseURL: baseURL || undefined,
    dangerouslyAllowBrowser: true,
    maxRetries: 3, // the SDK retries rate limits (429), overload (529) and server errors itself
  });
}

/** "Test connection": lists models. Costs nothing. */
export async function testClaude({ apiKey, baseURL }) {
  const client = makeClient({ apiKey, baseURL });
  await client.models.list({ limit: 1 });
  return true;
}

function systemPrompt({ kind, style, rules }) {
  const medium = kind === 'video' ? 'an AI video generator' : 'an AI image generator';
  const focus =
    kind === 'video'
      ? 'Each prompt describes: subject, what moves or happens, camera movement, setting, lighting and mood.'
      : 'Each prompt describes: subject, composition, setting, lighting, visual style and mood.';
  return [
    `You write prompts for ${medium}.`,
    'The user gives a base idea. Write the requested number of new prompts.',
    'Every prompt must be complete and standalone (about 30 to 80 words), and different from every other prompt, including the ones already written.',
    focus,
    `Variation: ${VARIATION_STYLES[style] || VARIATION_STYLES.angle_lighting}`,
    rules ? `Rules from the user that every prompt must follow:\n${rules}` : '',
    'Do not number the prompts. Do not add commentary.',
  ]
    .filter(Boolean)
    .join('\n\n');
}

/** One Claude call that returns up to `count` prompts. */
async function requestChunk(client, { model, kind, basePrompt, style, rules, count, existing }) {
  const recent = existing.slice(-150).map((p) => `- ${p.slice(0, 140)}`).join('\n');
  const userText = [
    `Base idea:\n${basePrompt}`,
    recent ? `Already written (do not repeat these):\n${recent}` : '',
    `Write exactly ${count} new prompts.`,
  ]
    .filter(Boolean)
    .join('\n\n');

  const params = {
    model,
    max_tokens: 16000,
    system: systemPrompt({ kind, style, rules }),
    messages: [{ role: 'user', content: userText }],
    output_config: { format: { type: 'json_schema', schema: OUTPUT_SCHEMA } },
  };
  // Haiku 4.5 does not accept the "effort" setting.
  if (!model.startsWith('claude-haiku')) params.output_config.effort = 'medium';

  let message;
  if (FALLBACK_MODELS.has(model)) {
    message = await client.beta.messages.create({
      ...params,
      betas: ['server-side-fallback-2026-07-01'],
      fallbacks: 'default',
    });
  } else {
    message = await client.messages.create(params);
  }

  if (message.stop_reason === 'refusal') {
    throw new Error('Claude declined to write these prompts. Try rewording your idea or rules.');
  }
  const textBlock = message.content.find((block) => block.type === 'text');
  if (!textBlock) throw new Error('Claude returned no text.');
  let parsed;
  try {
    parsed = JSON.parse(textBlock.text);
  } catch {
    throw new Error('Claude returned something that was not valid JSON. Please try again.');
  }
  return (parsed.prompts || []).map((p) => String(p).trim()).filter(Boolean);
}

/**
 * Expand one idea into `count` different prompts.
 * Calls Claude in chunks of 25 and checks the count itself
 * (the API cannot be told "exactly N items").
 *
 * @param {object} opts
 * @param {(done:number,total:number)=>void} [opts.onProgress]
 * @returns {Promise<string[]>}
 */
export async function expandPrompts({
  apiKey,
  baseURL,
  model,
  kind,
  basePrompt,
  style,
  rules,
  count,
  existing = [],
  onProgress,
  signal,
}) {
  if (!basePrompt?.trim()) throw new Error('Type a prompt first.');
  const client = makeClient({ apiKey, baseURL });
  const results = [];
  const seen = new Set(existing.map((p) => p.toLowerCase()));
  let emptyRounds = 0;

  while (results.length < count) {
    if (signal?.aborted) throw new Error('Stopped.');
    const want = Math.min(CHUNK, count - results.length);
    const chunk = await requestChunk(client, {
      model,
      kind,
      basePrompt,
      style,
      rules,
      count: want,
      existing: [...existing, ...results],
    });
    let added = 0;
    for (const prompt of chunk) {
      const key = prompt.toLowerCase();
      if (seen.has(key)) continue; // skip exact duplicates
      seen.add(key);
      results.push(prompt);
      added += 1;
      if (results.length >= count) break;
    }
    onProgress?.(results.length, count);
    emptyRounds = added === 0 ? emptyRounds + 1 : 0;
    if (emptyRounds >= 3) {
      throw new Error(`Claude stopped producing new prompts after ${results.length}. Try a broader idea or "wild" variations.`);
    }
  }
  return results;
}
