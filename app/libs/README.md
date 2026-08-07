# Put the Nosmai SDK here

Download the latest `nosmai-release.aar` from the releases page:

**https://github.com/nosmai/camera-sdk-android/releases**

and drop it in as:

```
app/libs/nosmai-release.aar
```

The AAR is **not committed** to this repository — it is ~36 MB, and a pinned copy
would go stale the moment a new SDK build ships. Taking it from releases each
time is how you get the current one. `.gitignore` excludes `*.aar` so a local
copy cannot be committed by accident.

Nothing else resolves it, so the build fails until you drop your copy in.

It is referenced from two places, both already wired up:

- `app/build.gradle.kts` — `implementation(files("libs/nosmai-release.aar"))`
- `settings.gradle.kts` — `flatDir { dirs("app/libs") }`

The SDK ships **arm64-v8a only**, which is why `app/build.gradle.kts` sets
`ndk { abiFilters += "arm64-v8a" }`.
