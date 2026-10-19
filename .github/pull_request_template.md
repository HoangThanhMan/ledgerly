## What and why

<!-- Two or three sentences. Link the issue: Closes #N -->

## Evidence

- [ ] New or changed tests: <!-- test names -->
- [ ] Ledger invariants I1–I7 still hold (if the change touches a money flow)
- [ ] Benchmark results (for `perf` changes): <!-- link to perf/results/... -->

## Checklist

- [ ] The PR title follows Conventional Commits
- [ ] Under about 400 changed lines, not counting tests and docs
- [ ] No `double` or `float` for money, no `@Transactional` outside `internal.application`
- [ ] README updated if the change affects it
