# Documentation Map

Last reviewed: August 11, 2026

Use this directory as the repository knowledge base. `AGENTS.md` is the short
entry point; these files hold the deeper product and engineering context. Read
only the documents relevant to the task, then verify claims against code and
tests.

| Document | Source of truth for | Read when changing |
| --- | --- | --- |
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | Current stage, shipped capabilities, open decisions, next sequence | Starting any new task or release work |
| [`APP_PLAN.md`](APP_PLAN.md) | Product purpose, scope, non-goals, and roadmap | Product behavior or feature scope |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Runtime flow, component ownership, state model, and behavioral invariants | Java structure, persistence, authorization, or Android APIs |
| [`RELIABILITY.md`](RELIABILITY.md) | Monitoring contract, recovery matrix, polling budgets, and platform limits | Service lifecycle, UsageStats, overlays, boot, battery, or health |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | Local setup, coding/style conventions, debug tooling, and routine validation | Implementing or testing code |
| [`PRODUCT_LANGUAGE.md`](PRODUCT_LANGUAGE.md) | Goose/Keyholder roles, voice, and approval-flow wording | UI, notifications, SMS copy, screenshots, or listing text |
| [`TEST_PLAN.md`](TEST_PLAN.md) | Release-level manual and device acceptance matrix | Behavior changes or release qualification |
| [`TEST_AUTOMATION_TODO.md`](TEST_AUTOMATION_TODO.md) | Remaining automation backlog and cadence | Test infrastructure or CI work |
| [`RELEASE.md`](RELEASE.md) | Signing, artifact creation, direct APK distribution, and Play workflow | Building or distributing a release |
| [`PLAY_CONSOLE_SUBMISSION.md`](PLAY_CONSOLE_SUBMISSION.md) | Current Play listing/declaration draft and external owner actions | Play Console submission |
| [`../PRIVACY.md`](../PRIVACY.md) | Public data-handling disclosure | Storage, permissions, data transfer, backup, or Play Data safety |

## Authority And Maintenance

- Code and tests define current mechanics. Documentation defines product intent,
  constraints, release procedure, and facts that are not obvious from code.
- `PROJECT_STATUS.md` may contain time-sensitive release facts. Update it when a
  release is uploaded, validation completes, an external account state changes,
  or a major decision is made.
- Keep durable rules in `AGENTS.md`; do not copy full architecture or release
  instructions into it.
- Update one canonical document and link to it instead of duplicating detailed
  tables across files. `RELIABILITY.md` owns polling/recovery numbers;
  `RELEASE.md` owns signing steps; `PRIVACY.md` owns public data claims.
- Dates mean the file was checked against the repository on that date, not that
  every external policy link is permanently current. Re-check Play and Android
  policy sources at submission time.

This layout follows current Codex guidance: repository instructions are layered
by directory, so the root `AGENTS.md` stays small and points to deeper sources
instead of becoming an encyclopedia. References:

- <https://developers.openai.com/codex/guides/agents-md/>
- <https://openai.com/index/harness-engineering/>
