# E2E Record: task 110 no-extra-includedomains

## Scope

- Backend: `http://localhost:9093`
- Health: `UP`
- Run dir: `E:\java_study\Mul-agnet\tmp\stage1-degraded-live-e2e-no-extra-includedomains-20260712-162239`
- Task id: `110`
- Request: Douyin vs Bilibili POC, same business scenario as task 109
- Important difference from task 109: no manual multi-domain `include_domains` patch was applied.

## Pre-execute Config Check

- Request JSON contains no `includeDomains` field.
- No DB patch was applied to node configs.
- OFFICIAL/DOCS kept the system-generated single official-domain constraint.
- REVIEW top-level `includeDomains` stayed open.
- The multi-domain white-list used in task 109 was not added.

## Final Status

- Task status: `SUCCESS`
- Status summary: Stage 1 degraded deliverable, manual review recommended
- Completed nodes: `14/14`
- `canViewReport`: `true`
- `canViewDraftReport`: `true`
- Report endpoint: available
- Evidence endpoint: `14` evidences
- Report `evidenceCount`: `14`
- Report `sourceUrls`: `27`
- Report `qualityPassed`: `false`
- Report `qualityScore`: `36`
- Report `rewriteApplied`: `false`

## Node Status Summary

- `SUCCESS_DEGRADED`: 5
- `SUCCESS`: 6
- `FAILED`: 1
- `SKIPPED`: 2

Failed/skipped nodes:

- `rewrite_report`: `FAILED`
  - Error: `LLM call failed: OpenAiHttpException: "Invalid token"`
- `citation_check_revision`: `SKIPPED`
  - Reason: `Dependencies not satisfied: rewrite_report=FAILED`
- `quality_check_final`: `SKIPPED`
  - Reason: `Dependencies not satisfied: citation_check_revision=SKIPPED`

## Collector Summary

| Node | Type | Status | readyForQuorum | evidenceFragments | totalCollected | successCollected | degradationReason |
|---|---|---|---:|---:|---:|---:|---|
| collect_sources_01_01 | OFFICIAL | SUCCESS_DEGRADED | false | 0 | 0 | 0 | HARD_DEADLINE_REACHED |
| collect_sources_01_02 | DOCS | SUCCESS_DEGRADED | true | 4 | 8 | 4 | HARD_DEADLINE_REACHED |
| collect_sources_01_03 | REVIEW | SUCCESS_DEGRADED | true | 3 | 5 | 3 | HARD_DEADLINE_REACHED |
| collect_sources_02_01 | OFFICIAL | SUCCESS_DEGRADED | true | 1 | 2 | 1 | HARD_DEADLINE_REACHED |
| collect_sources_02_02 | DOCS | SUCCESS | true | 8 | 8 | 4 | SEARCH_TIMEOUT_BEFORE_SUPPLEMENT |
| collect_sources_02_03 | REVIEW | SUCCESS_DEGRADED | true | 2 | 9 | 2 | HARD_DEADLINE_REACHED |

## Interpretation

This run passed the full Stage 1 flow at the task level. It did not reproduce task 109's blocker where collector quorum failed and `extract_schema` was skipped.

The important confirmation is that removing the manual multi-domain `include_domains` patch restored enough evidence for the collector quorum gate:

- `extract_schema` ran and succeeded.
- `analyze_competitors` ran and succeeded.
- `write_report` ran and succeeded.
- `citation_check` ran and succeeded.
- The report is visible.

Remaining issue:

- The first quality review failed with score `36`.
- The automatic rewrite path then failed because the LLM provider returned `Invalid token`.
- Because the Stage 1 degraded-deliverable policy accepted the already generated report with traceable sources, the task closed as `SUCCESS`.

## Conclusion

For E2E stability, do not manually add the broader multi-domain `include_domains` patch used in task 109. It over-constrained or distorted the search/collection path and caused collector quorum to fail. The no-extra-includedomains run reaches a report-visible Stage 1 degraded deliverable.
