#Requires -Version 7.0
<#
.SYNOPSIS
  Read, measure, or move this repository's pinned wire-format corpus revision.

.DESCRIPTION
  `corpus-pin.json` at the repository root records ONE commit of the conformance
  corpus (`fuaran-ui/fuaran-ui-specification`) — the revision this host's gates
  certify against. CI checks the corpus out AT that commit, so a corpus commit no
  longer reddens this repository with no change of its own.

  A PIN, not a SNAPSHOT, and that distinction is why this file exists rather than a
  copy of the sibling hosts' tooling. The TypeScript and Python hosts BUNDLE the
  corpus payload and record the revision it was taken from, so their sentinel
  answers "are these bytes current". This repository bundles nothing: it reads the
  corpus from a sibling checkout, so what is recorded here is which revision the
  reader is aimed at. The record shape is deliberately the same (`authorityCommit`,
  40 lowercase hex) so one estate-wide question has one estate-wide answer; the
  `kind` is `corpusPin` rather than `corpusSnapshot` because a reader that treated
  the two alike would go looking for a payload that is not here.

  The pin does NOT make drift invisible — that would trade a red gate for a silent
  one. Every run reports the distance from the pin to the corpus's current head, by
  number, and a pinned CI run prints it as a notice. Moving the pin is a REVIEWED
  act: it adopts whatever vocabulary the corpus gained in between, so it lands in
  the same change-set as the decoder work that adopts it, never on its own.

  What it covers HERE: `:fuaran-ui:corpusCheck` (the decode harness),
  `:fuaran-renderer:testDebugUnitTest` (the Compose render-coverage and
  render-obligation gates) and the corpus-driven accessibility harnesses all read
  the corpus this record selects — so one pin decides what all of them certify
  against, and moving it is one reviewed act rather than four.

.PARAMETER Authority
  The corpus clone to measure against. Defaults to the canonical side-by-side
  layout, `<repo>/../wire-format-fixtures`.

.PARAMETER Against
  The revision in that clone the pin is measured against. Defaults to `origin/main`,
  falling back to `HEAD` when the clone has no such remote-tracking ref — which is
  what makes this work both in a pinned CI checkout (HEAD is the pin; `origin/main`
  is the real head) and in an ordinary clone (where the two are the same commit).

.PARAMETER Assert
  Additionally require the authority checkout to BE at the pin, and exit 3 when it
  is not. This is what keeps the pin load-bearing: without it, dropping the `ref:`
  from the corpus checkout would silently return CI to certifying against whatever
  happened to be current, and every gate would still report green.

.PARAMETER Write
  Record the authority's current `-Against` revision as the pin. The reviewed move.

.OUTPUTS
  Exit 0 — the pin is the comparison revision; nothing to adopt.
  Exit 2 — the pin is BEHIND (or otherwise differs from) it. Reported, not fatal.
  Exit 1 — UNMEASURED: no corpus beside this checkout, not a git clone, or no pin
           recorded. Kept apart from 2 on purpose — a check that reported drift it
           had not measured would be the same vacuous claim this guard exists to
           prevent, read backwards.
  Exit 3 — `-Assert` only: the corpus checkout is not at the pin.
#>
[CmdletBinding()]
param(
    [string] $Authority,
    [string] $Against = "origin/main",
    [switch] $Assert,
    [switch] $Write
)

$ErrorActionPreference = "Stop"

# Seeded before any stage reads it: PowerShell leaves $LASTEXITCODE UNSET until the
# session's first native command, and `$null -ne 0` is TRUE — so a check reached
# before `git` has run once reports a failure that did not happen.
$LASTEXITCODE = 0

$Repo = Split-Path $PSScriptRoot -Parent
$PinFile = Join-Path $Repo "corpus-pin.json"
$PinKind = "corpusPin"
$AuthorityRepository = "fuaran-ui/fuaran-ui-specification"

if (-not $Authority) { $Authority = Join-Path $Repo ".." "wire-format-fixtures" }

function Write-Note($msg) { Write-Host $msg }
function Write-Drift($msg) { Write-Host $msg -ForegroundColor Yellow }

function Invoke-Git([string] $Dir, [string[]] $GitArgs) {
    # A git error is an ANSWER here (no such ref, not a clone), never an exception:
    # every caller below distinguishes "cannot tell" from "differs", and a throw
    # would collapse the two into one.
    $LASTEXITCODE = 0
    $out = & git -C $Dir @GitArgs 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    if ($null -eq $out) { return $null }
    return ($out | Select-Object -First 1).Trim()
}

function Read-Pin {
    if (-not (Test-Path $PinFile)) { return $null }
    try { $payload = Get-Content -Raw $PinFile | ConvertFrom-Json } catch { return $null }
    $sha = $payload.authorityCommit
    if ($sha -is [string] -and $sha -match '^[0-9a-f]{40}$') { return $sha }
    return $null
}

function Set-Pin([string] $Sha) {
    # Two-space indent, sorted members, trailing newline, LF — the byte shape the
    # sibling hosts' sentinels carry, so a data artefact does not churn on whichever
    # host last wrote it.
    $json = @"
{
  "authorityCommit": "$Sha",
  "authorityRepository": "$AuthorityRepository",
  "kind": "$PinKind"
}

"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($PinFile, ($json -replace "`r`n", "`n"), $utf8NoBom)
}

if (-not (Test-Path (Join-Path $Authority "manifest.json"))) {
    Write-Drift "corpus drift UNMEASURED: no conformance corpus at $Authority — nothing to measure the pin against."
    exit 1
}

# Resolve the comparison revision. `origin/main` is the honest default: in a pinned
# checkout HEAD *is* the pin, so measuring against HEAD would report a distance of
# zero forever. A clone with no remote-tracking ref must still answer, hence the
# fallback.
$againstSha = Invoke-Git $Authority @("rev-parse", "--verify", "--quiet", "$Against^{commit}")
$againstName = $Against
if (-not $againstSha) {
    $againstSha = Invoke-Git $Authority @("rev-parse", "--verify", "--quiet", "HEAD^{commit}")
    $againstName = "HEAD"
}
if (-not $againstSha) {
    Write-Drift "corpus drift UNMEASURED: cannot read '$Against' or HEAD in $Authority (not a git clone, or git is unavailable)."
    exit 1
}

if ($Write) {
    Set-Pin $againstSha
    Write-Note "corpus pin recorded: $againstSha (from $againstName in $Authority)"
    Write-Note "Moving the pin ADOPTS whatever the corpus gained in between — commit it with the decoder change that adopts it, never on its own."
    exit 0
}

$pin = Read-Pin
if (-not $pin) {
    Write-Drift "corpus drift UNMEASURED: $PinFile records no 40-character authorityCommit — record one with: pwsh ./dev-scripts/corpus-pin.ps1 -Write"
    exit 1
}

if ($Assert) {
    $head = Invoke-Git $Authority @("rev-parse", "--verify", "--quiet", "HEAD^{commit}")
    if (-not $head) {
        Write-Drift "corpus pin NOT ASSERTED: cannot read HEAD in $Authority."
        exit 1
    }
    if ($head -ne $pin) {
        Write-Host "the corpus checkout is NOT at the pin." -ForegroundColor Red
        Write-Host "  pinned:   $pin" -ForegroundColor Red
        Write-Host "  checkout: $head" -ForegroundColor Red
        Write-Host "The gates would certify against a revision this repository has not adopted." -ForegroundColor Red
        Write-Host "In CI that means the corpus checkout lost its 'ref:' — restore it rather than moving the pin." -ForegroundColor Red
        exit 3
    }
    Write-Note "corpus checkout is at the pin ($pin)."
}

if ($pin -eq $againstSha) {
    Write-Note "corpus pin is current ($pin) — nothing to adopt."
    exit 0
}

$behind = Invoke-Git $Authority @("rev-list", "--count", "$pin..$againstSha")
if ($null -eq $behind -or $behind -notmatch '^\d+$') {
    # Most commonly a shallow clone, which holds the comparison commit but not the
    # pinned one. Say so rather than guessing at a number.
    $headline = "corpus pin behind by an UNKNOWN number of commits (a shallow clone cannot count — check the corpus out at full depth)"
}
elseif ([int]$behind -eq 0) {
    # The comparison revision is not a descendant of the pin: rewritten history, or
    # another branch. Not "behind", and calling it that would be a guess.
    $headline = "corpus pin does not match $againstName and is not behind it (rewritten history, or another branch)"
}
else {
    $plural = if ([int]$behind -eq 1) { "" } else { "s" }
    $headline = "corpus pin behind $againstName by $behind commit$plural"
}

Write-Drift "$headline (pinned $pin, $againstName $againstSha)"
Write-Drift "Moving the pin is a REVIEWED act: it adopts whatever vocabulary the corpus gained in between, so it belongs in the same change-set as the decoder work that adopts it."
Write-Drift "  pwsh ./dev-scripts/corpus-pin.ps1 -Write"
exit 2
