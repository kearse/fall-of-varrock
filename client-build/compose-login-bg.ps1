# Composite the Fall of Varrock login background: hero-bg.jpg cover-cropped to
# 765x503 + the site's overlay (dark #080506 base, red glow up top, bottom fade
# so the centered login box stays readable).
Add-Type -AssemblyName System.Drawing
$src = "C:\Program Files (x86)\Kearse RSPS\client-build\assets\hero-bg.jpg"
$dst = $args[0]
$W = 765; $H = 503

$hero = [System.Drawing.Image]::FromFile($src)
$bmp  = New-Object System.Drawing.Bitmap($W, $H)
$g    = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

# cover-crop hero to 765x503
$scale = [Math]::Max($W / $hero.Width, $H / $hero.Height)
$dw = [int]($hero.Width * $scale); $dh = [int]($hero.Height * $scale)
$dx = [int](($W - $dw) / 2); $dy = [int](($H - $dh) / 2)
$g.DrawImage($hero, $dx, $dy, $dw, $dh)

# dark base overlay (#080506 @ ~42%)
$dark = [System.Drawing.Color]::FromArgb(108, 8, 5, 6)
$g.FillRectangle((New-Object System.Drawing.SolidBrush($dark)), 0, 0, $W, $H)

# bottom fade to near-black (login box sits center-bottom) — vertical gradient
$rect = New-Object System.Drawing.Rectangle(0, [int]($H*0.45), $W, [int]($H*0.55))
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, ([System.Drawing.Color]::FromArgb(0,8,5,6)), ([System.Drawing.Color]::FromArgb(200,8,5,6)), 90)
$g.FillRectangle($lg, $rect)

# top red glow (site: rgba(220,38,38,0.14)) — radial via PathGradientBrush
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddEllipse(-100, -260, $W + 200, 460)
$pg = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
$pg.CenterPoint = New-Object System.Drawing.PointF([single]($W/2), [single]40)
$pg.CenterColor = [System.Drawing.Color]::FromArgb(70, 220, 38, 38)
$pg.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 220, 38, 38))
$g.FillPath($pg, $path)

# subtle top darkening for the RuneScape logo area contrast
$rectTop = New-Object System.Drawing.Rectangle(0, 0, $W, [int]($H*0.30))
$lgTop = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rectTop, ([System.Drawing.Color]::FromArgb(150,8,5,6)), ([System.Drawing.Color]::FromArgb(0,8,5,6)), 90)
$g.FillRectangle($lgTop, $rectTop)

$g.Dispose()
$bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Jpeg)
$bmp.Dispose(); $hero.Dispose()
Write-Host "wrote $dst ($W x $H)"
