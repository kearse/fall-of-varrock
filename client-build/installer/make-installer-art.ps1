# Generates the two WiX UI bitmaps for the Fall of Varrock installer from the
# shield mark. Sizes are fixed by WiX: banner 493x58, dialog 493x312, and on the
# dialog only the left 164px is artwork -- the rest must be the dialog's white so
# the text controls sit on a clean background.
Add-Type -AssemblyName System.Drawing

$repo   = "C:\Program Files (x86)\Kearse RSPS"
$shield = [Drawing.Image]::FromFile((Join-Path $repo "client-build\assets\fov-shield.png"))
$outDir = $args[0]
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$DARK   = [Drawing.Color]::FromArgb(0x0B, 0x07, 0x08)
$CRIM   = [Drawing.Color]::FromArgb(0xDC, 0x26, 0x26)
$WHITE  = [Drawing.Color]::White

function New-Canvas([int]$w, [int]$h) {
    # 24bpp, no alpha: MSI will not render a 32-bit BMP reliably.
    $bmp = New-Object Drawing.Bitmap($w, $h, [Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode  = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode      = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode    = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @($bmp, $g)
}

function Draw-Shield($g, [int]$cx, [int]$cy, [int]$targetH) {
    $scale = $targetH / $shield.Height
    $w = [int]($shield.Width * $scale)
    $g.DrawImage($shield, ($cx - $w / 2), ($cy - $targetH / 2), $w, $targetH)
}

# ---- banner.bmp (493x58) -- title text sits left, mark goes right ----
$c = New-Canvas 493 58
$bmp, $g = $c[0], $c[1]
$g.Clear($WHITE)
Draw-Shield $g 455 27 46
# Thin crimson rule grounds the banner against the white dialog body.
$g.FillRectangle((New-Object Drawing.SolidBrush($CRIM)), 0, 56, 493, 2)
$g.Dispose()
$bmp.Save((Join-Path $outDir "banner.bmp"), [Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Save((Join-Path $outDir "banner-preview.png"), [Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

# ---- dialog.bmp (493x312) -- left 164px art band, remainder white ----
$c = New-Canvas 493 312
$bmp, $g = $c[0], $c[1]
$g.Clear($WHITE)
$g.FillRectangle((New-Object Drawing.SolidBrush($DARK)), 0, 0, 164, 312)

# Crimson glow behind the shield. Clipped to the art band: the ellipse is wider
# than 164px and would otherwise smear pink across the white text area.
$g.SetClip((New-Object Drawing.Rectangle(0, 0, 162, 312)))
$path = New-Object Drawing.Drawing2D.GraphicsPath
$path.AddEllipse(-40, 40, 244, 244)
$glow = New-Object Drawing.Drawing2D.PathGradientBrush($path)
$glow.CenterPoint  = New-Object Drawing.PointF(82, 162)
$glow.CenterColor  = [Drawing.Color]::FromArgb(120, $CRIM)
$glow.SurroundColors = @([Drawing.Color]::FromArgb(0, $CRIM))
$g.FillEllipse($glow, -40, 40, 244, 244)

Draw-Shield $g 82 150 170
$g.ResetClip()
# Crimson edge where the art band meets the white text area.
$g.FillRectangle((New-Object Drawing.SolidBrush($CRIM)), 162, 0, 2, 312)
$g.Dispose()
$bmp.Save((Join-Path $outDir "dialog.bmp"), [Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Save((Join-Path $outDir "dialog-preview.png"), [Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

$shield.Dispose()

Get-ChildItem $outDir -Filter *.bmp | ForEach-Object {
    $img = [Drawing.Image]::FromFile($_.FullName)
    "{0,-12} {1}x{2}  {3}  {4:N0} bytes" -f $_.Name, $img.Width, $img.Height, $img.PixelFormat, $_.Length
    $img.Dispose()
}
