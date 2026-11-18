# One-time move of an install made under the project's previous name
# (dot-sourced by install-windows.ps1 and update-windows.ps1).
#
# ImPrahari VMS was published under another name before. An install from then
# keeps its settings in .env and its data in Docker volumes named after that
# previous name. These move it over; each step does nothing when there is
# nothing to move, so they are safe on every run. The previous name isn't
# written anywhere in this project: Find-PreviousName reads it from the
# install itself (see scripts/lib-env.sh for the same on Linux).

$NewName = "imprahari"
$RenameMarker = ".moved-from-previous-name"
$script:PreviousName = $null

function Test-Volume { param([string]$Name) $ErrorActionPreference = "Continue"; docker volume inspect $Name *> $null; return ($LASTEXITCODE -eq 0) }

# The previous name, or "" if this install never had one. Looks, in order, at:
# the container this compose project runs its backend in ("<name>-backend");
# the marker a finished move left in the new data volume; a "<name>-data"
# volume holding "<name>.db" (the stack was already recreated under the new name).
function Find-PreviousName {
    $ErrorActionPreference = "Continue"
    if ($null -ne $script:PreviousName) { return $script:PreviousName }
    $script:PreviousName = ""
    $id = @(docker compose ps -a -q backend 2>$null) | Select-Object -First 1
    if ($id) {
        $name = ((docker inspect -f '{{.Name}}' $id 2>$null) | Out-String).Trim().TrimStart('/')
        if ($name -match '^([a-z0-9_]+)-backend$' -and $Matches[1] -ne $NewName) {
            $script:PreviousName = $Matches[1]; return $script:PreviousName
        }
    }
    if (Test-Volume "$NewName-data") {
        $name = ((docker run --rm -v "$NewName-data:/d:ro" alpine cat "/d/$RenameMarker" 2>$null) | Out-String).Trim()
        if ($name -match '^[a-z0-9_]+$') { $script:PreviousName = $name; return $script:PreviousName }
    }
    foreach ($v in @(docker volume ls -q 2>$null)) {
        if ($v -match '^([a-z0-9_]+)-data$' -and $Matches[1] -ne $NewName) {
            $name = $Matches[1]
            docker run --rm -v "${v}:/d:ro" alpine test -f "/d/$name.db" *> $null
            if ($LASTEXITCODE -eq 0) { $script:PreviousName = $name; return $script:PreviousName }
        }
    }
    return $script:PreviousName
}

# Rename the previous name's .env settings to IMPRAHARI_*; point DATABASE_URL at the new file.
function Move-FormerEnv {
    param([string]$EnvFile)
    $previous = Find-PreviousName
    if (-not $previous -or -not (Test-Path $EnvFile)) { return }
    $prefix = $previous.ToUpperInvariant().Replace('-', '_') + "_"
    $changed = $false
    $out = foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
        $l = [string]$line
        if ($l.StartsWith($prefix)) {
            $l = "IMPRAHARI_" + $l.Substring($prefix.Length); $changed = $true
        }
        if ($l.StartsWith("DATABASE_URL=") -and $l.EndsWith("/$previous.db")) {
            $l = $l.Substring(0, $l.Length - "$previous.db".Length) + "imprahari.db"; $changed = $true
        }
        $l
    }
    if ($changed) {
        # No byte-order mark: docker compose would read it as part of the first key.
        [System.IO.File]::WriteAllLines($EnvFile, [string[]]$out, (New-Object System.Text.UTF8Encoding $false))
        Write-Host "==> Renamed the $($prefix)* settings in .env to IMPRAHARI_*." -ForegroundColor Cyan
    }
}

# Copy the old volume into the new one with every container using either one
# stopped (the database is copied at rest). Runs when the new volume doesn't
# exist yet - or exists without the marker and is less than a day old: then an
# updater from before the rename (or a manual `docker compose up`) already
# started the new version on an empty volume. Whatever that empty start wrote
# is kept inside the new volume under .replaced-<time>/. $false = couldn't move.
function Move-FormerVolume {
    param([string]$Old, [string]$New, [bool]$Keep)
    # Native tools write to stderr routinely; under "Stop" PowerShell 5.1 would treat that as fatal.
    $ErrorActionPreference = "Continue"
    if (-not (Test-Volume $Old)) { return $true }
    $made = $false
    if (Test-Volume $New) {
        docker run --rm -v "${New}:/to:ro" alpine test -e "/to/$RenameMarker" *> $null
        if ($LASTEXITCODE -eq 0) { return $true }
        $created = (docker volume inspect -f '{{.CreatedAt}}' $New | Out-String).Trim()
        $age = [TimeSpan]::MaxValue
        try { $age = [DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($created) } catch { }
        if ($age.TotalHours -gt 24) {
            Write-Host "!!  Both $Old and $New exist and $New is in use - leaving both as they are." -ForegroundColor Yellow
            return $true
        }
    } else {
        docker volume create $New | Out-Null
        $made = $true
    }
    $need = [long]((docker run --rm -v "${Old}:/from:ro" alpine du -sk /from | Out-String).Trim() -split '\s+')[0]
    $free = [long](((docker run --rm -v "${New}:/to" alpine df -Pk /to | Select-Object -Last 1) -split '\s+')[3])
    if ($need + 1048576 -gt $free) {
        if ($made) { docker volume rm $New *> $null }
        if (-not $Keep) {
            Write-Host "!!  Not enough free disk to copy $Old - its downloads will be fetched again when needed." -ForegroundColor Yellow
            return $true
        }
        Write-Host "ERROR: Not enough free disk to copy your data ($Old, $([int]($need / 1024)) MB) to $New. Free some space and run the update again - nothing was changed." -ForegroundColor Red
        return $false
    }
    Write-Host "==> Moving your data to the new name: Docker volume $Old -> $New ($([int]($need / 1024)) MB)." -ForegroundColor Cyan
    $ids = @(@(docker ps -q --filter "volume=$Old") + @(docker ps -q --filter "volume=$New") | Where-Object { $_ } | Sort-Object -Unique)
    if ($ids.Count) { docker stop -t 45 @ids | Out-Null }
    $copy = 'set -e; if [ -n "$(ls -A /to)" ]; then aside="/to/.replaced-$(date +%Y%m%d-%H%M%S)"; mkdir "$aside"; ' +
            'for f in /to/* /to/.[!.]*; do [ -e "$f" ] && [ "$f" != "$aside" ] && case "$f" in /to/.replaced-*) ;; *) mv "$f" "$aside/" ;; esac; done; fi; ' +
            "cp -a /from/. /to/; echo `"`$PREVIOUS`" > /to/$RenameMarker"
    docker run --rm -e "PREVIOUS=$(Find-PreviousName)" -v "${Old}:/from:ro" -v "${New}:/to" alpine sh -c $copy
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: Copying $Old to $New failed - $Old is untouched. Run the update again." -ForegroundColor Red
        if ($ids.Count) { docker start @ids | Out-Null }
        return $false
    }
    if ($Keep) {
        Write-Host "==> Done. $Old is kept as a backup; once everything works, remove it with: docker volume rm $Old" -ForegroundColor Cyan
    } else {
        docker volume rm $Old *> $null
    }
    return $true
}

# The data and describer volumes; $false if one couldn't move.
function Move-FormerVolumes {
    $previous = Find-PreviousName
    if (-not $previous) { return $true }
    if (-not (Move-FormerVolume "$previous-data" "$NewName-data" $true)) { return $false }
    if (-not (Move-FormerVolume "$previous-describe-models" "$NewName-describe-models" $false)) { return $false }
    return $true
}

# After a healthy start: images this project published under another name, and
# the previous name's compose network (never anything a container still uses).
function Remove-FormerLeftovers {
    $ErrorActionPreference = "Continue"
    $inUse = @(docker ps -a --format '{{.Image}}')
    docker image ls --format '{{.Repository}}:{{.Tag}}' |
        Where-Object { $_ -match '^ghcr\.io/manojmkss/[^/]+-(backend|frontend|ai-worker):' -and
                       $_ -notlike "ghcr.io/manojmkss/$NewName-*" -and $inUse -notcontains $_ } |
        ForEach-Object { docker image rm $_ *> $null }
    $previous = Find-PreviousName
    if ($previous) {
        docker network ls -q --filter "label=com.docker.compose.network=$previous" |
            ForEach-Object { docker network rm $_ *> $null }
    }
}
