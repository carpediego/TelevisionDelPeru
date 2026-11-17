#!/usr/bin/env node
/**
 * Valida el catálogo de canales y, opcionalmente, repara el orden de las URLs.
 *
 *   node tools/validate.mjs          -> solo informa (no modifica nada)
 *   node tools/validate.mjs --fix    -> reordena, cuenta fallos y sube la versión
 *
 * Lo ejecuta el robot de GitHub Actions cada 6 horas, pero también se puede
 * correr a mano desde el PC antes de publicar un APK.
 *
 * Regla que no se rompe: el validador NUNCA escribe URLs resueltas. Muchos
 * proveedores redirigen a una CDN con token que caduca en minutos; guardar esa
 * dirección dejaría el canal muerto al rato. Solo se reordena lo que ya está.
 */

import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const CATALOG_PATH = path.join(ROOT, "catalog", "channels.json");
const FIX = process.argv.includes("--fix");

const TIMEOUT_MS = 10_000;
const USER_AGENT =
  "TelevisionDelPeru-Validator/1.0 (+https://github.com/carpediego/TelevisionDelPeru)";
const MAX_PLAYLIST_BYTES = 512 * 1024;
const MAX_CANDIDATES = 3;
const FAILS_BEFORE_HIDING = 5;
const CONCURRENCY = 4;

// ------------------------------------------------------------------ utilidades

function withTimeout(ms) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  return { signal: controller.signal, done: () => clearTimeout(timer) };
}

/** Lee como mucho [limit] bytes y corta: una lista HLS nunca es enorme. */
async function readTextLimited(res, limit) {
  const reader = res.body?.getReader();
  if (!reader) return await res.text();
  const chunks = [];
  let total = 0;
  try {
    while (total < limit) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      total += value.byteLength;
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* da igual */
    }
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8").decode(merged);
}

async function fetchWithTimeout(url, headers = {}) {
  const { signal, done } = withTimeout(TIMEOUT_MS);
  try {
    return await fetch(url, {
      redirect: "follow",
      signal,
      headers: { "User-Agent": USER_AGENT, Accept: "*/*", ...headers },
    });
  } finally {
    done();
  }
}

/** Comprueba que el primer segmento de una lista de segmentos responda de verdad. */
async function probeSegment(mediaUrl, mediaText) {
  const lines = mediaText.split(/\r?\n/).map((l) => l.trim());
  const segments = lines.filter((line) => line && !line.startsWith("#"));
  if (segments.length === 0) return { ok: false, error: "lista sin segmentos" };

  // El primero suele ser el más antiguo; en un directo puede estar ya borrado.
  // Se prueban el primero y el último antes de darlo por roto.
  const targets = [segments[0]];
  if (segments.length > 1) targets.push(segments[segments.length - 1]);

  let lastError = "sin segmentos";
  for (const raw of targets) {
    const segmentUrl = new URL(raw, mediaUrl).toString();
    try {
      const res = await fetchWithTimeout(segmentUrl, { Range: "bytes=0-4095" });
      const type = (res.headers.get("content-type") || "").toLowerCase();
      if (!(res.status === 200 || res.status === 206)) {
        lastError = `segmento HTTP ${res.status}`;
        try {
          await res.body?.cancel();
        } catch {
          /* nada */
        }
        continue;
      }
      if (type.includes("text/html")) {
        lastError = "segmento HTML";
        try {
          await res.body?.cancel();
        } catch {
          /* nada */
        }
        continue;
      }
      const reader = res.body?.getReader();
      let bytes = 0;
      let head = "";
      if (reader) {
        const { value } = await reader.read();
        if (value) {
          bytes = value.byteLength;
          head = new TextDecoder("utf-8").decode(value.slice(0, 16));
        }
        try {
          await reader.cancel();
        } catch {
          /* nada */
        }
      }
      if (bytes <= 0) {
        lastError = "segmento vacío";
        continue;
      }
      if (/^\s*</.test(head)) {
        lastError = "segmento HTML";
        continue;
      }
      return { ok: true, detail: `segmento ${res.status} ${type.split(";")[0] || "binario"}` };
    } catch (err) {
      lastError = err?.name === "AbortError" ? "timeout de segmento" : String(err?.message ?? err);
    }
  }
  return { ok: false, error: lastError };
}

/** Valida un candidato HLS: manifiesto, variante y primer segmento. */
async function probeHls(url) {
  let res;
  try {
    res = await fetchWithTimeout(url);
  } catch (err) {
    return {
      ok: false,
      error: err?.name === "AbortError" ? "timeout" : String(err?.message ?? err),
    };
  }

  try {
    const type = (res.headers.get("content-type") || "").toLowerCase();
    if (!(res.status >= 200 && res.status < 300)) {
      return { ok: false, error: `HTTP ${res.status} ${type.split(";")[0]}` };
    }
    if (type.includes("text/html")) {
      return { ok: false, error: "responde HTML (no es un stream)" };
    }

    let text = await readTextLimited(res, MAX_PLAYLIST_BYTES);
    if (!text.includes("#EXTM3U")) {
      return { ok: false, error: "no es una lista HLS (#EXTM3U ausente)" };
    }

    const baseUrl = res.url || url;

    // ¿Es un manifiesto maestro? Entonces hay que bajar a la primera variante.
    if (text.includes("#EXT-X-STREAM-INF")) {
      const variantCount = (text.match(/#EXT-X-STREAM-INF/g) || []).length;
      const lines = text.split(/\r?\n/).map((l) => l.trim());
      let variant = null;
      for (let i = 0; i < lines.length; i++) {
        if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
          for (let j = i + 1; j < lines.length; j++) {
            if (lines[j] && !lines[j].startsWith("#")) {
              variant = new URL(lines[j], baseUrl).toString();
              break;
            }
          }
          if (variant) break;
        }
      }
      if (!variant) return { ok: false, error: "manifiesto maestro sin variantes" };

      let vres;
      try {
        vres = await fetchWithTimeout(variant);
      } catch (err) {
        return {
          ok: false,
          error: err?.name === "AbortError" ? "timeout de variante" : "variante inaccesible",
        };
      }
      const vtype = (vres.headers.get("content-type") || "").toLowerCase();
      if (!(vres.status >= 200 && vres.status < 300)) {
        return { ok: false, error: `variante HTTP ${vres.status} ${vtype.split(";")[0]}` };
      }
      if (vtype.includes("text/html")) {
        return { ok: false, error: "variante HTML" };
      }
      text = await readTextLimited(vres, MAX_PLAYLIST_BYTES);
      if (!text.includes("#EXTM3U") || !text.includes("#EXTINF")) {
        return { ok: false, error: "variante sin segmentos (#EXTINF)" };
      }
      const segment = await probeSegment(vres.url || variant, text);
      if (!segment.ok) return { ok: false, error: segment.error };
      return {
        ok: true,
        detail: `maestro con ${variantCount} variante(s) · ${segment.detail}`,
      };
    }

    if (!text.includes("#EXTINF")) {
      return { ok: false, error: "lista sin segmentos (#EXTINF ausente)" };
    }
    const segment = await probeSegment(baseUrl, text);
    if (!segment.ok) return { ok: false, error: segment.error };
    return { ok: true, detail: segment.detail };
  } catch (err) {
    return {
      ok: false,
      error: err?.name === "AbortError" ? "timeout" : String(err?.message ?? err),
    };
  }
}

/** Los canales de este proyecto son HLS; se deja la puerta abierta a DASH. */
async function probeCandidate(candidate) {
  const type = String(candidate.type || "hls").toLowerCase();
  if (type === "dash" || /\.mpd(\?|$)/i.test(candidate.url)) {
    return { ok: false, error: "DASH todavía no lo valida este robot" };
  }
  return probeHls(candidate.url);
}

async function mapLimit(items, limit, worker) {
  const results = new Array(items.length);
  let next = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (true) {
      const index = next++;
      if (index >= items.length) return;
      results[index] = await worker(items[index], index);
    }
  });
  await Promise.all(runners);
  return results;
}

// ---------------------------------------------------------------------- main

const raw = await readFile(CATALOG_PATH, "utf8");
const catalog = JSON.parse(raw);
const channels = Array.isArray(catalog.channels) ? catalog.channels : [];

if (channels.length === 0) {
  console.error("El catálogo no tiene canales. No se toca nada.");
  process.exit(1);
}

console.log(`Catálogo v${catalog.version} · ${channels.length} canales\n`);

const verdicts = await mapLimit(channels, CONCURRENCY, async (channel) => {
  const candidates = channel.candidates ?? [];
  let workingIndex = -1;
  let detail = "";
  const errors = [];

  for (let i = 0; i < candidates.length; i++) {
    const result = await probeCandidate(candidates[i]);
    if (result.ok) {
      workingIndex = i;
      detail = result.detail ?? "";
      break;
    }
    errors.push(`[${i + 1}] ${result.error}`);
  }

  return { channel, workingIndex, detail, errors };
});

const broken = verdicts.filter((v) => v.workingIndex < 0);

// Si falla casi todo, el problema es la red del runner (o del PC), no el catálogo.
if (broken.length > channels.length * 0.5) {
  console.error(
    `\n${broken.length} de ${channels.length} canales fallaron. ` +
      "Es más de la mitad: se aborta sin tocar el catálogo."
  );
  process.exit(1);
}

let changed = false;
let okCount = 0;
const reordered = [];
const hidden = [];

for (const verdict of verdicts) {
  const { channel, workingIndex, detail, errors } = verdict;
  const candidates = channel.candidates ?? [];

  if (workingIndex >= 0) {
    okCount++;
    console.log(`  OK    ${channel.name}  (${detail})`);

    if (FIX) {
      // Si la que funciona no es la primera, se sube para ahorrar un intento fallido.
      if (workingIndex > 0) {
        const [winner] = candidates.splice(workingIndex, 1);
        candidates.unshift(winner);
        reordered.push(channel.name);
        changed = true;
        console.log(`        reordenada: ahora usa el candidato ${workingIndex + 1}`);
      }

      const wasHidden = channel.active === false;
      const wasFailing = (channel.health?.fails ?? 0) > 0;
      if (wasFailing || wasHidden) {
        channel.health = { fails: 0, lastOk: new Date().toISOString() };
        channel.active = true;
        changed = true;
      }
    }
    continue;
  }

  console.log(`  FALLA ${channel.name}  (${errors.join(" · ")})`);

  if (!FIX) continue;

  const fails = (channel.health?.fails ?? 0) + 1;
  channel.health = { ...(channel.health ?? {}), fails };
  changed = true;

  if (fails >= FAILS_BEFORE_HIDING && channel.active !== false) {
    channel.active = false;
    hidden.push(channel.name);
    console.log(`        sin candidato válido tras ${fails} intentos: se oculta`);
  } else {
    console.log(`        sin candidato válido (intento ${fails}/${FAILS_BEFORE_HIDING})`);
  }
}

// Un catálogo con menos de 3 canales activos no sirve: la app lo ignoraría.
const activeCount = channels.filter((c) => c.active !== false).length;
if (FIX && activeCount < 3) {
  console.error(
    `\nSolo quedarían ${activeCount} canales activos. No se escribe nada (la app exige 3).`
  );
  process.exit(1);
}

console.log(
  `\nResumen: ${okCount} ok · ${broken.length} con problemas · ` +
    `${reordered.length} reordenados · ${hidden.length} ocultos`
);

if (FIX && changed) {
  catalog.version = Number(catalog.version ?? 0) + 1;
  catalog.updated = new Date().toISOString();
  await writeFile(CATALOG_PATH, `${JSON.stringify(catalog, null, 2)}\n`, "utf8");
  console.log(`Catálogo actualizado a v${catalog.version}`);
} else if (FIX) {
  console.log("Sin cambios: el catálogo queda igual.");
}
