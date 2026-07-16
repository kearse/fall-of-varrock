# Wiki images

Drop screenshots and other wiki images here. They're served at `/wiki-images/<file>`.

Embed in any `content/wiki/*.md` article with standard markdown:

    ![A short caption](/wiki-images/fight-cave-jad.png)

- A line that is **only** an image renders as a captioned figure (the alt text becomes the caption).
- An image inside a sentence renders inline.
- Only **root-relative** paths (`/wiki-images/...`) are allowed — remote URLs are ignored for privacy/safety.

Suggested naming: `<topic>-<subject>.png`, lowercase, hyphenated (e.g. `shops-quartermaster-relics.png`).
