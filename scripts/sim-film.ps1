# Offline simulation of the battle film's shot picking (FilmPicker), old vs new. No game or Hytale jar needed.
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'build\sim'
New-Item -ItemType Directory -Force $out | Out-Null
javac -d $out (Join-Path $root 'src\main\java\dev\dackoro\mobbattle\FilmPicker.java') (Join-Path $PSScriptRoot 'FilmPickerSim.java')
if ($LASTEXITCODE -eq 0) { java -cp $out dev.dackoro.mobbattle.FilmPickerSim }
