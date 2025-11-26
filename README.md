# Comic Converter - CBZ to PDF

An Android app that converts CBZ (Comic Book ZIP) files to PDF format.

## Features

- Select CBZ files from your device
- Automatically extracts images from the CBZ archive
- Reads metadata from `index.json` file
- Creates a PDF with:
  - Proper title from metadata
  - Author information
  - Publisher information
  - Cover image (specified in metadata or first image)
  - All comic pages in order

## CBZ File Format

The app expects CBZ files with the following structure:
```
comic.cbz (ZIP file)
├── index.json (optional metadata)
├── page001.jpg
├── page002.jpg
├── page003.jpg
└── ...
```

### index.json Format

```json
{
  "title": "Comic Title",
  "author": "Author Name",
  "publisher": "Publisher Name",
  "cover": "page001.jpg"
}
```

All fields are optional. If not provided:
- `title` defaults to the CBZ filename
- `cover` defaults to the first image in alphabetical order

## How to Use

1. Launch the app
2. Tap "Select CBZ File"
3. Choose a CBZ file from your device
4. Wait for the conversion to complete
5. The PDF will be saved to: `/storage/emulated/0/Documents/ComicConverter/`

## Requirements

- Android 12 (API 31) or higher
- Storage permissions (automatically requested)

## Technical Details

- Uses iText7 for PDF generation
- Supports image formats: JPG, JPEG, PNG, GIF, BMP, WEBP
- Images are compressed to JPEG at 85% quality to reduce file size
- Pages are sized to fit A4 format
- Runs conversion on background thread to avoid blocking UI

## Build

```bash
./gradlew build
```

## Install

```bash
./gradlew installDebug
```

