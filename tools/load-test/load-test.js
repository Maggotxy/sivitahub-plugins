"use strict";

const fs = require("node:fs");
const path = require("node:path");
const mineflayer = require("mineflayer");

const integer = (name, fallback, minimum, maximum) => {
  const value = Number.parseInt(process.env[name] ?? String(fallback), 10);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer between ${minimum} and ${maximum}`);
  }
  return value;
};

const ratio = (name, fallback) => {
  const value = Number.parseFloat(process.env[name] ?? String(fallback));
  if (!Number.isFinite(value) || value < 0 || value > 1) {
    throw new Error(`${name} must be a number between 0 and 1`);
  }
  return value;
};

const decimal = (name, fallback, minimum, maximum) => {
  const value = Number.parseFloat(process.env[name] ?? String(fallback));
  if (!Number.isFinite(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be a number between ${minimum} and ${maximum}`);
  }
  return value;
};

const bool = (name, fallback) => {
  const value = (process.env[name] ?? String(fallback)).toLowerCase();
  if (value === "true") return true;
  if (value === "false") return false;
  throw new Error(`${name} must be true or false`);
};

if (process.env.LOAD_TEST_ACK !== "private-server-only") {
  throw new Error("Set LOAD_TEST_ACK=private-server-only after confirming the target is your own staging server");
}

const host = process.env.MC_HOST ?? "127.0.0.1";
const localTargets = new Set(["127.0.0.1", "localhost", "::1"]);
if (!localTargets.has(host) && process.env.ALLOW_REMOTE_TARGET !== "true") {
  throw new Error("Remote targets are blocked by default; set ALLOW_REMOTE_TARGET=true only for your own host");
}

const options = {
  host,
  port: integer("MC_PORT", 25565, 1, 65535),
  count: integer("BOT_COUNT", 10, 1, 120),
  rampMs: integer("RAMP_MS", 500, 100, 10000),
  durationMinutes: integer("DURATION_MINUTES", 30, 1, 240),
  mode: (process.env.BOT_MODE ?? "mixed").toLowerCase(),
  authMode: (process.env.AUTHME_MODE ?? "auto").toLowerCase(),
  password: process.env.AUTHME_TEST_PASSWORD ?? "",
  minimumSpawnRatio: ratio("MIN_SPAWN_RATIO", 0.95),
  minimumConnectedRatio: ratio("MIN_CONNECTED_RATIO", 0.90),
  maximumKicks: integer("MAX_KICKS", 0, 0, 120),
  maximumErrors: integer("MAX_ERRORS", 0, 0, 10_000),
  maximumP95Mspt: decimal("MAX_P95_MSPT", 45, 1, 1_000),
  maximumP99Mspt: decimal("MAX_P99_MSPT", 50, 1, 1_000),
  minimumTps: decimal("MIN_TPS", 19, 0, 20),
};
if (!["idle", "explore", "combat", "mixed"].includes(options.mode)) {
  throw new Error("BOT_MODE must be idle, explore, combat, or mixed");
}
if (!["none", "auto", "register", "login"].includes(options.authMode)) {
  throw new Error("AUTHME_MODE must be none, auto, register, or login");
}
if (options.password && options.password.length < 8) {
  throw new Error("AUTHME_TEST_PASSWORD must be empty or at least eight characters");
}
if (options.authMode !== "none" && !options.password) {
  throw new Error("AUTHME_TEST_PASSWORD is required unless AUTHME_MODE=none");
}

const startedAt = new Date();
const defaultStatusFile = localTargets.has(host)
  ? path.resolve(__dirname, "../../server/runtime/plugins/RpgPerformance/status.json")
  : "";
const performanceStatusFile = process.env.PERFORMANCE_STATUS_FILE
  ? path.resolve(process.env.PERFORMANCE_STATUS_FILE)
  : defaultStatusFile;
const requirePerformanceMetrics = bool("REQUIRE_PERFORMANCE_METRICS", Boolean(performanceStatusFile));
const defaultReportName = `load-test-${startedAt.toISOString().replace(/[:.]/g, "-")}.json`;
const reportFile = path.resolve(process.env.LOAD_TEST_REPORT
  ?? path.join(__dirname, "reports", defaultReportName));

const bots = new Map();
const timers = new Set();
const counters = { created: 0, spawned: 0, kicked: 0, errors: 0, ended: 0, peakConnected: 0 };
const kickReasons = [];
const performanceSamples = [];
let stopping = false;

const later = (callback, delay) => {
  const timer = setTimeout(() => { timers.delete(timer); callback(); }, delay);
  timers.add(timer);
  return timer;
};

function behaviorFor(index) {
  if (options.mode !== "mixed") return options.mode;
  return ["idle", "explore", "combat"][index % 3];
}

function authenticate(bot) {
  if (options.authMode === "none") return;
  if (options.authMode === "login") {
    bot.chat(`/login ${options.password}`);
    return;
  }
  bot.chat(`/register ${options.password} ${options.password}`);
  if (options.authMode === "auto") {
    later(() => { if (!stopping && bot.entity) bot.chat(`/login ${options.password}`); }, 1200);
  }
}

function collectPerformanceSample() {
  if (!performanceStatusFile) return;
  try {
    const value = JSON.parse(fs.readFileSync(performanceStatusFile, "utf8"));
    const sampledAt = Date.parse(value.sampledAt);
    if (!Number.isFinite(sampledAt) || Date.now() - sampledAt > 30_000) return;
    const previous = performanceSamples.at(-1);
    if (previous?.sampledAt === value.sampledAt) return;
    performanceSamples.push({
      sampledAt: value.sampledAt,
      state: String(value.state ?? "UNKNOWN"),
      tps: Number(value.tps),
      mspt: Number(value.mspt),
      players: Number(value.players),
      chunks: Number(value.chunks),
      entities: Number(value.entities),
      usedHeapBytes: Number(value.usedHeapBytes),
      jdbcWaiting: Number(value.jdbcWaiting),
      jdbcQueue: Number(value.jdbcQueue),
    });
  } catch (error) {
    if (error?.code !== "ENOENT") {
      process.stderr.write(`[metrics] ${error.message}\n`);
    }
  }
}

function percentile(values, quantile) {
  const finite = values.filter(Number.isFinite).sort((left, right) => left - right);
  if (finite.length === 0) return null;
  const index = Math.max(0, Math.ceil(finite.length * quantile) - 1);
  return finite[index];
}

function maximum(values) {
  const finite = values.filter(Number.isFinite);
  return finite.length === 0 ? null : Math.max(...finite);
}

function minimum(values) {
  const finite = values.filter(Number.isFinite);
  return finite.length === 0 ? null : Math.min(...finite);
}

function scheduleMovement(bot, behavior) {
  if (behavior === "idle") return;
  const changeDirection = () => {
    if (stopping || !bot.entity) return;
    for (const control of ["forward", "back", "left", "right", "jump", "sprint"]) {
      bot.setControlState(control, false);
    }
    const directions = ["forward", "left", "right"];
    bot.setControlState(directions[Math.floor(Math.random() * directions.length)], true);
    bot.setControlState("sprint", true);
    if (Math.random() < 0.35) bot.setControlState("jump", true);
    void bot.look(Math.random() * Math.PI * 2, (Math.random() - 0.5) * 0.35, true);
    later(changeDirection, 2500 + Math.floor(Math.random() * 4500));
  };
  changeDirection();

  if (behavior === "combat") {
    const attack = () => {
      if (stopping || !bot.entity) return;
      const target = bot.nearestEntity((entity) => entity.type === "mob"
        && entity.position.distanceTo(bot.entity.position) <= 4.2);
      if (target) bot.attack(target);
      later(attack, 700 + Math.floor(Math.random() * 500));
    };
    later(attack, 1000);
  }
}

function createBot(index) {
  if (stopping) return;
  const username = `svtlt${String(index + 1).padStart(3, "0")}`;
  const bot = mineflayer.createBot({ host: options.host, port: options.port, username, auth: "offline" });
  bots.set(username, bot);
  counters.created++;
  bot.once("spawn", () => {
    counters.spawned++;
    counters.peakConnected = Math.max(counters.peakConnected, connectedBots());
    authenticate(bot);
    scheduleMovement(bot, behaviorFor(index));
  });
  bot.on("kicked", (reason) => {
    counters.kicked++;
    if (kickReasons.length < 20) kickReasons.push(String(reason).slice(0, 500));
  });
  bot.on("error", (error) => {
    counters.errors++;
    process.stderr.write(`[${username}] ${error.message}\n`);
  });
  bot.on("end", () => {
    counters.ended++;
    bots.delete(username);
  });
}

function connectedBots() {
  let connected = 0;
  for (const bot of bots.values()) if (bot.entity) connected++;
  return connected;
}

function performanceSummary() {
  const mspt = performanceSamples.map((sample) => sample.mspt);
  const tps = performanceSamples.map((sample) => sample.tps);
  const states = { NORMAL: 0, PRESSURE: 0, CRITICAL: 0, UNKNOWN: 0 };
  for (const sample of performanceSamples) {
    const key = Object.hasOwn(states, sample.state) ? sample.state : "UNKNOWN";
    states[key]++;
  }
  return {
    samples: performanceSamples.length,
    p95Mspt: percentile(mspt, 0.95),
    p99Mspt: percentile(mspt, 0.99),
    maximumMspt: maximum(mspt),
    minimumTps: minimum(tps),
    peakPlayers: maximum(performanceSamples.map((sample) => sample.players)),
    peakChunks: maximum(performanceSamples.map((sample) => sample.chunks)),
    peakEntities: maximum(performanceSamples.map((sample) => sample.entities)),
    peakUsedHeapBytes: maximum(performanceSamples.map((sample) => sample.usedHeapBytes)),
    peakJdbcWaiting: maximum(performanceSamples.map((sample) => sample.jdbcWaiting)),
    peakJdbcQueue: maximum(performanceSamples.map((sample) => sample.jdbcQueue)),
    states,
  };
}

function stop(requestedExitCode = 0) {
  if (stopping) return;
  stopping = true;
  for (const timer of timers) clearTimeout(timer);
  timers.clear();
  collectPerformanceSample();
  const connectedAtEnd = connectedBots();
  const spawnRatio = counters.spawned / options.count;
  const connectedRatio = connectedAtEnd / options.count;
  const performance = performanceSummary();
  const failures = [];
  if (spawnRatio < options.minimumSpawnRatio) {
    failures.push(`spawn ratio ${spawnRatio.toFixed(3)} is below ${options.minimumSpawnRatio}`);
  }
  if (connectedRatio < options.minimumConnectedRatio) {
    failures.push(`connected ratio ${connectedRatio.toFixed(3)} is below ${options.minimumConnectedRatio}`);
  }
  if (counters.kicked > options.maximumKicks) {
    failures.push(`kicks ${counters.kicked} exceed ${options.maximumKicks}`);
  }
  if (counters.errors > options.maximumErrors) {
    failures.push(`client errors ${counters.errors} exceed ${options.maximumErrors}`);
  }
  if (requirePerformanceMetrics && performance.samples < 3) {
    failures.push(`only ${performance.samples} fresh performance samples were collected`);
  }
  if (performance.p95Mspt !== null && performance.p95Mspt > options.maximumP95Mspt) {
    failures.push(`P95 MSPT ${performance.p95Mspt} exceeds ${options.maximumP95Mspt}`);
  }
  if (performance.p99Mspt !== null && performance.p99Mspt > options.maximumP99Mspt) {
    failures.push(`P99 MSPT ${performance.p99Mspt} exceeds ${options.maximumP99Mspt}`);
  }
  if (performance.minimumTps !== null && performance.minimumTps < options.minimumTps) {
    failures.push(`minimum TPS ${performance.minimumTps} is below ${options.minimumTps}`);
  }
  if (performance.states.CRITICAL > 0) failures.push("server entered CRITICAL load state");
  if ((performance.peakJdbcWaiting ?? 0) > 0) failures.push("JDBC connection waiters were observed");

  const report = {
    schemaVersion: 1,
    startedAt: startedAt.toISOString(),
    endedAt: new Date().toISOString(),
    target: `${options.host}:${options.port}`,
    workload: {
      bots: options.count,
      mode: options.mode,
      authMode: options.authMode,
      rampMs: options.rampMs,
      durationMinutes: options.durationMinutes,
    },
    gates: {
      minimumSpawnRatio: options.minimumSpawnRatio,
      minimumConnectedRatio: options.minimumConnectedRatio,
      maximumKicks: options.maximumKicks,
      maximumErrors: options.maximumErrors,
      maximumP95Mspt: options.maximumP95Mspt,
      maximumP99Mspt: options.maximumP99Mspt,
      minimumTps: options.minimumTps,
      requirePerformanceMetrics,
    },
    clients: { ...counters, connectedAtEnd, spawnRatio, connectedRatio, kickReasons },
    performance,
    verdict: failures.length === 0 && requestedExitCode === 0 ? "PASS" : "FAIL",
    failures,
    requestedExitCode,
  };
  fs.mkdirSync(path.dirname(reportFile), { recursive: true });
  fs.writeFileSync(reportFile, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
  process.stdout.write(`report=${reportFile} verdict=${report.verdict}`
    + `${failures.length ? ` failures=${failures.join("; ")}` : ""}\n`);
  for (const bot of bots.values()) bot.quit("load test complete");
  const exitCode = requestedExitCode !== 0 ? requestedExitCode : failures.length === 0 ? 0 : 2;
  later(() => process.exit(exitCode), 1500);
}

process.stdout.write(`target=${options.host}:${options.port} bots=${options.count} mode=${options.mode}`
  + ` auth=${options.authMode} rampMs=${options.rampMs} report=${reportFile}\n`);
collectPerformanceSample();
for (let index = 0; index < options.count; index++) later(() => createBot(index), index * options.rampMs);

const report = setInterval(() => {
  collectPerformanceSample();
  const memory = process.memoryUsage();
  const connected = connectedBots();
  counters.peakConnected = Math.max(counters.peakConnected, connected);
  process.stdout.write(`${new Date().toISOString()} connected=${connected} created=${counters.created} spawned=${counters.spawned} kicked=${counters.kicked} errors=${counters.errors} performanceSamples=${performanceSamples.length} rssMiB=${(memory.rss / 1048576).toFixed(1)}\n`);
}, 10_000);
report.unref();

later(() => stop(counters.spawned === 0 ? 2 : 0), options.count * options.rampMs + options.durationMinutes * 60_000);
process.on("SIGINT", () => stop(130));
process.on("SIGTERM", () => stop(143));
