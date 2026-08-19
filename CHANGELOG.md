# Changelog

## 0.1.0 (unreleased)

First release: a Kotlin Multiplatform SDK for the spoo.me v1 API, targeting
Android and the JVM.

- Full v1 coverage: shorten (alphanumeric and emoji aliases), alias check,
  link management, bulk delete/status/expiry/domain, claiming anonymous
  links, account and per-link statistics, streaming exports, public stats
  and previews, the emoji catalogue (ETag-cached), identity read.
- Authentication: API keys, anonymous mode, and Sign in with Spoo (PKCE,
  code exchange, self-refreshing single-flight sessions with redacting
  token types).
- Coroutines-first surface: suspend functions, Flow pagination, coroutine
  cancellation always rethrown untouched.
- Tri-state updates: untouched fields keep their stored values, remove*
  methods clear a setting explicitly.
- Typed sealed errors with the backend's machine-readable codes,
  rate-limit metadata, and the 401 trichotomy (session expired, link
  password, plain unauthorized) kept distinguishable.
- Automatic retries with jittered backoff capped at 8 seconds, honoring
  both legal Retry-After forms with a 60 second ceiling; POST and PATCH
  replay only where the server provably did no work.
- Server-suggested export filenames are sanitized to safe bare names.
- Raw typed passthroughs (get/post/patch/delete) for endpoints the SDK
  does not cover yet.
- Consumer R8 rules ship in the artifact; Ktor engine is injectable with
  OkHttp as the platform default.
