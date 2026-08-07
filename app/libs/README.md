# Put the Nosmai SDK here

```
app/libs/nosmai-release.aar
```

The AAR is distributed separately and is deliberately not committed
(`.gitignore` excludes `app/libs/*.aar`). Nothing else resolves it, so the build
fails until you drop your copy in.

It is referenced from two places, both already wired up:

- `app/build.gradle.kts` — `implementation(files("libs/nosmai-release.aar"))`
- `settings.gradle.kts` — `flatDir { dirs("app/libs") }`

The SDK ships **arm64-v8a only**, which is why `app/build.gradle.kts` sets
`ndk { abiFilters += "arm64-v8a" }`.
