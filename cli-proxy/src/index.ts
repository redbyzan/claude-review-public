import express from "express";
import { spawn } from "node:child_process";

const app = express();
app.use(express.json({ limit: "1mb" }));

const CLI_PROVIDERS: Record<string, {
  bin: string;
  args: string[];
  timeout: number;
  stdinPrompt: boolean;
}> = {
  gemini: {
    bin: process.env.GEMINI_BIN || "gemini",
    args: ["-p", "-"],
    timeout: 60_000,
    stdinPrompt: true,
  },
  codex: {
    bin: process.env.CODEX_BIN || "codex",
    args: ["exec"],
    timeout: 90_000,
    stdinPrompt: true,
  },
};

function runCli(
  provider: string,
  prompt: string
): Promise<{ stdout: string; stderr: string }> {
  const cli = CLI_PROVIDERS[provider] || CLI_PROVIDERS.gemini;

  return new Promise((resolve, reject) => {
    const proc = spawn(cli.bin, cli.args, { timeout: cli.timeout });

    let stdout = "";
    let stderr = "";
    proc.stdout.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
    proc.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });

    proc.on("close", (code) => {
      if (code === 0) resolve({ stdout, stderr });
      else reject(new Error(`exit code ${code}: ${stderr || stdout}`));
    });
    proc.on("error", reject);

    if (cli.stdinPrompt) {
      proc.stdin.write(prompt);
      proc.stdin.end();
    }
  });
}

// POST /review
app.post("/review", async (req, res) => {
  const { systemPrompt, userPrompt, provider = "gemini" } = req.body as {
    systemPrompt?: string;
    userPrompt?: string;
    provider?: string;
  };

  if (!systemPrompt || !userPrompt) {
    return res.status(400).json({ error: "systemPrompt, userPrompt required" });
  }

  const fullPrompt = `${systemPrompt}\n\n---\n\n${userPrompt}`;
  const start = Date.now();

  try {
    const { stdout } = await runCli(provider, fullPrompt);
    const elapsed = Date.now() - start;
    console.log(`[CLI] ${provider} | ${elapsed}ms | ${stdout.length}chars`);

    res.json({
      review: stdout.trim(),
      usage: null,
      model: provider,
      elapsedMs: elapsed,
    });
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    console.error(`[CLI] ${provider} failed:`, message);
    res.status(502).json({
      error: `${provider} CLI 실행 실패`,
      detail: message,
    });
  }
});

// GET /health
app.get("/health", (_, res) => {
  res.json({ status: "ok", uptime: Math.floor(process.uptime()) });
});

const PORT = parseInt(process.env.PORT || "3100", 10);
const BIND = process.env.BIND || "0.0.0.0";
app.listen(PORT, BIND, () => {
  console.log(`[cli-proxy] 로컬 AI CLI 프록시 시작 — ${BIND}:${PORT}`);
  console.log(`[cli-proxy] gemini: ${CLI_PROVIDERS.gemini.bin} ${CLI_PROVIDERS.gemini.args.join(" ")}`);
  console.log(`[cli-proxy] codex:  ${CLI_PROVIDERS.codex.bin} ${CLI_PROVIDERS.codex.args.join(" ")}`);
});
