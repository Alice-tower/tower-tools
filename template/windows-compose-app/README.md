# __APP_DISPLAY_NAME__

__APP_DESCRIPTION__

- Project: `__APP_PROJECT_NAME__`
- Application ID: `__APP_ID__`
- Version: `__APP_VERSION__`
- Author: `Alice-tower`

## Run

```powershell
.\gradlew.bat run
```

## Build portable directory

```powershell
.\gradlew.bat createDistributable
```

The repository lifecycle scripts copy the resulting Windows application image into the shared `outputs/` directory.
