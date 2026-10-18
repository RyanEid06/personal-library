# Memora task instructions

## Durable V1.0.1 context

Read `docs/CODEX_V1_0_1_MASTER_PROMPT.md` and `docs/CODEX_V1_0_1_WORKLOG.md` before continuing V1.0.1 work after a context reset. The master prompt is the complete immutable specification; the worklog records current state, decisions, checks, evidence, and next steps. Follow the mandatory phase order and commit verified checkpoints. Preserve existing unrelated work.

## Subagent model routing — owner instruction, 2026-10-18

- Reading, investigation, debugging, checking, and review subagents: **GPT-6 Luna**, model `gpt-6-luna`, reasoning effort **high** (the maximum to use for these jobs).
- Subagents writing substantial production code: **GPT-6.1 Sol**, model `gpt-6.1-sol`, reasoning effort **high**.
- Set model and reasoning effort explicitly when spawning a subagent. Use `fork_turns: "none"` with a self-contained brief.
- Do not use Astra, a higher reasoning tier, or a different model for these roles without a newer owner instruction. Earlier general model preferences are superseded by this rule.
- If an existing agent has the wrong model for a new role, spawn the correct model for that role rather than reusing the agent.
- Reviewers must challenge behavior and test gaps rather than merely agree with the implementation.

## Local verification environment

Use JDK 21, the repository's `.tools/android-sdk`, and the current Gradle wrapper. For local Gradle on this Windows host, set process-local `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<absolute-repository-path>\.tools` and `ANDROID_HOME`/`ANDROID_SDK_ROOT` to the SDK path, and use `--no-daemon --no-configuration-cache --console=plain`. Required cache writes and local socket-based tests may need sandbox escalation. Never print signing credentials or provider secrets.
