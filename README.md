# Personal Library

An Android application built with Kotlin and Jetpack Compose to replace WhatsApp "Random Links" archives with durable local storage, organized browsing, local full-text search, and Gemini-assisted retrieval.

## Overview & Core Loop

The application provides a seamless **save -> find -> use** loop:
- **Save from WhatsApp & Other Apps**: Android Share menu integration (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`) allows sharing photos, videos, audio voice notes, links, documents, and text messages directly into Personal Library with one tap.
- **Durable Local Storage**: Original files and documents are stored privately on-device in app-specific storage with SHA-256 deduplication and SQLite full-text search (`FTS4`). Originals remain available independently of WhatsApp.
- **Type Filtering & Grouping**: Browse across Photos, Stickers, Notes & Text, Links, Audio, Videos, Documents, and Files. Filter or sort by saved date, source message date, or title.
- **Folder-like Collections**: Logical collections (e.g. *Random Links*, *University & Grades*, *Important*, *Memes & Stickers*) plus computed views (*Favorites*, *Unfiled*, *Due Reminders*).
- **Assisted Search & Voice**: Instant keyword search through local FTS4 and "Ask AI" natural language retrieval with match explanations. Includes English speech recognition via Android `SpeechRecognizer`.
- **WhatsApp Migration Tool**: Built-in chat export parser to import previous "Random Links" chats, parsing dates, senders, notes, and media attachment references.
- **Backup & Trash**: Versioned JSON backup export and staged restore with duplicate detection; 30-day Trash with restore and permanent purge; local storage breakdown.
- **Reminders**: Local notifications scheduled via Android's `AlarmManager`.

## Tech Stack & Architecture

- **Runtime**: Android SDK 36 (target/compile), API 29 (minimum)
- **UI**: Jetpack Compose, Material 3, Edge-to-Edge window insets
- **Language**: Kotlin 2.2.10
- **Database**: SQLite with FTS4 virtual table for local search indexing
- **Image Loading**: Coil Compose 2.7.0
- **AI Integration**: Gemini 3.5 Flash REST API integration with graceful local semantic fallback
- **Network & Serialization**: Retrofit 2.12.0, OkHttp 4.10.0, Kotlinx Serialization 1.7.3
- **Secrets Management**: Secrets Gradle Plugin configured with `.env` / `.env.example`
