import {
  ItemEntity,
  CollectionEntity,
  ItemCollectionCrossRef,
  ReminderEntity,
  SearchDocumentEntity,
  StorageStats,
} from '../types';

const STORAGE_KEYS = {
  ITEMS: 'personal_library_items_v1',
  COLLECTIONS: 'personal_library_collections_v1',
  CROSS_REFS: 'personal_library_cross_refs_v1',
  REMINDERS: 'personal_library_reminders_v1',
  SEARCH_DOCS: 'personal_library_search_docs_v1',
  APP_LOCK_PIN: 'personal_library_pin_v1',
  APP_LOCK_ENABLED: 'personal_library_lock_enabled_v1',
};

const INITIAL_COLLECTIONS: CollectionEntity[] = [
  { id: 'col_university', name: 'University', iconName: 'GraduationCap', colorHex: '#3B82F6', createdAt: Date.now() - 86400000 * 30 },
  { id: 'col_random_links', name: 'Random Links', iconName: 'Share2', colorHex: '#10B981', createdAt: Date.now() - 86400000 * 20 },
  { id: 'col_important', name: 'Important', iconName: 'Bookmark', colorHex: '#F59E0B', createdAt: Date.now() - 86400000 * 15 },
  { id: 'col_projects', name: 'Projects', iconName: 'FolderGit2', colorHex: '#8B5CF6', createdAt: Date.now() - 86400000 * 10 },
];

const INITIAL_ITEMS: ItemEntity[] = [
  {
    id: 'item_chem_grades',
    title: 'Chemistry Midterm Exam Grades & Breakdown',
    content: 'Organic Chemistry Midterm: Score 94/100 (A). Section average was 78. Final review will cover stereochemistry and synthesis reaction mechanisms.',
    kind: 'DOCUMENT',
    isFavorite: true,
    isTrash: false,
    trashTimestamp: null,
    createdAt: Date.now() - 86400000 * 25, // ~25 days ago
    sourceDate: Date.now() - 86400000 * 26,
    reminderTime: Date.now() + 86400000 * 2, // In 2 days
    isReminderDone: false,
    contentHash: 'hash_chem_grades_01',
    originalFileName: 'chem_midterm_grades.pdf',
    mimeType: 'application/pdf',
    fileSizeBytes: 245000,
    aiDescription: 'Academic grade report for Organic Chemistry midterm showing score 94/100 and study notes.',
    aiConcepts: 'grades, chemistry, exam, university, academic, score',
    aiStatus: 'READY',
  },
  {
    id: 'item_campus_library',
    title: 'Science Library Study Room Reservation',
    content: 'Reserved Study Room 3B at Science Library for study group revision on Thursday from 2:00 PM to 5:00 PM. Access code: 4920.',
    kind: 'PHOTO',
    isFavorite: false,
    isTrash: false,
    trashTimestamp: null,
    createdAt: Date.now() - 86400000 * 14,
    sourceDate: Date.now() - 86400000 * 14,
    reminderTime: null,
    isReminderDone: false,
    contentHash: 'hash_room_res_02',
    originalFileName: 'library_room_booking_qr.png',
    mimeType: 'image/png',
    fileSizeBytes: 612000,
    aiDescription: 'Screenshot confirmation of university library room 3B booking with QR code and 4920 access code.',
    aiConcepts: 'university, library, reservation, study, screenshot',
    aiStatus: 'READY',
    fileData: 'https://images.unsplash.com/photo-1541829070764-84a7d30dd3f3?auto=format&fit=crop&w=600&q=80',
  },
  {
    id: 'item_react_docs',
    title: 'Modern React Architecture & Component Patterns',
    content: 'Comprehensive overview of state management, custom hooks, and streaming patterns for scalable apps.',
    kind: 'LINK',
    isFavorite: true,
    isTrash: false,
    trashTimestamp: null,
    createdAt: Date.now() - 86400000 * 7,
    sourceDate: Date.now() - 86400000 * 7,
    reminderTime: null,
    isReminderDone: false,
    contentHash: 'hash_react_link_03',
    originalFileName: null,
    mimeType: 'text/html',
    fileSizeBytes: 1400,
    aiDescription: 'Web link to React architecture guide covering state patterns and hooks.',
    aiConcepts: 'react, coding, web, documentation, link, programming',
    aiStatus: 'READY',
    extractedUrl: 'https://react.dev/learn/thinking-in-react',
  },
  {
    id: 'item_thumbsup_sticker',
    title: 'Thumbs Up WhatsApp Cat Sticker',
    content: 'Funny celebratory sticker saved from Random Links group chat.',
    kind: 'STICKER',
    isFavorite: true,
    isTrash: false,
    trashTimestamp: null,
    createdAt: Date.now() - 86400000 * 5,
    sourceDate: Date.now() - 86400000 * 5,
    reminderTime: null,
    isReminderDone: false,
    contentHash: 'hash_cat_sticker_04',
    originalFileName: 'cat_thumbs_up.webp',
    mimeType: 'image/webp',
    fileSizeBytes: 48000,
    aiDescription: 'Transparent WhatsApp sticker of an animated smiling cat giving two thumbs up.',
    aiConcepts: 'sticker, cat, celebration, meme, whatsapp',
    aiStatus: 'READY',
    fileData: 'https://images.unsplash.com/photo-1514888286974-6c03e2ca1dba?auto=format&fit=crop&w=400&q=80',
  },
  {
    id: 'item_lecture_audio',
    title: 'Biochemistry Lecture Audio Memo',
    content: 'Prof. Miller explanation on enzyme kinetics and Michaelis-Menten constant determination. Listen before Friday quiz.',
    kind: 'AUDIO',
    isFavorite: false,
    isTrash: false,
    trashTimestamp: null,
    createdAt: Date.now() - 86400000 * 3,
    sourceDate: Date.now() - 86400000 * 3,
    reminderTime: Date.now() + 86400000 * 1, // Tomorrow
    isReminderDone: false,
    contentHash: 'hash_biochem_audio_05',
    originalFileName: 'lecture_enzymes_notes.m4a',
    mimeType: 'audio/mp4',
    fileSizeBytes: 1850000,
    aiDescription: 'Voice recording of university biochemistry lecture on enzyme kinetics and quiz preparation.',
    aiConcepts: 'audio, lecture, university, biochemistry, enzymes, notes',
    aiStatus: 'READY',
  },
  {
    id: 'item_receipt_laptop',
    title: 'Laptop Repair Invoice & Warranty Slip',
    content: 'Authorized repair service receipt. Battery replacement ($85.00) and keyboard cleaning. 90-day parts warranty expires January 2027.',
    kind: 'DOCUMENT',
    isFavorite: false,
    isTrash: false,
    trashTimestamp: null,
    createdAt: Date.now() - 86400000 * 2,
    sourceDate: Date.now() - 86400000 * 2,
    reminderTime: null,
    isReminderDone: false,
    contentHash: 'hash_repair_invoice_06',
    originalFileName: 'repair_invoice_nov.pdf',
    mimeType: 'application/pdf',
    fileSizeBytes: 180000,
    aiDescription: 'Official electronic receipt for hardware battery replacement and 90-day warranty coverage.',
    aiConcepts: 'receipt, invoice, warranty, laptop, expense, repair',
    aiStatus: 'READY',
  }
];

const INITIAL_CROSS_REFS: ItemCollectionCrossRef[] = [
  { itemId: 'item_chem_grades', collectionId: 'col_university' },
  { itemId: 'item_chem_grades', collectionId: 'col_important' },
  { itemId: 'item_campus_library', collectionId: 'col_university' },
  { itemId: 'item_react_docs', collectionId: 'col_random_links' },
  { itemId: 'item_react_docs', collectionId: 'col_projects' },
  { itemId: 'item_thumbsup_sticker', collectionId: 'col_random_links' },
  { itemId: 'item_lecture_audio', collectionId: 'col_university' },
  { itemId: 'item_receipt_laptop', collectionId: 'col_important' },
];

export class StorageService {
  private static load<T>(key: string, defaultValue: T): T {
    try {
      const data = localStorage.getItem(key);
      return data ? JSON.parse(data) : defaultValue;
    } catch {
      return defaultValue;
    }
  }

  private static save<T>(key: string, value: T): void {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      console.warn('LocalStorage save failed:', e);
    }
  }

  static getItems(): ItemEntity[] {
    const items = this.load<ItemEntity[]>(STORAGE_KEYS.ITEMS, INITIAL_ITEMS);
    return items;
  }

  static saveItems(items: ItemEntity[]): void {
    this.save(STORAGE_KEYS.ITEMS, items);
  }

  static getCollections(): CollectionEntity[] {
    return this.load<CollectionEntity[]>(STORAGE_KEYS.COLLECTIONS, INITIAL_COLLECTIONS);
  }

  static saveCollections(collections: CollectionEntity[]): void {
    this.save(STORAGE_KEYS.COLLECTIONS, collections);
  }

  static getCrossRefs(): ItemCollectionCrossRef[] {
    return this.load<ItemCollectionCrossRef[]>(STORAGE_KEYS.CROSS_REFS, INITIAL_CROSS_REFS);
  }

  static saveCrossRefs(refs: ItemCollectionCrossRef[]): void {
    this.save(STORAGE_KEYS.CROSS_REFS, refs);
  }

  static getReminders(): ReminderEntity[] {
    return this.load<ReminderEntity[]>(STORAGE_KEYS.REMINDERS, []);
  }

  static saveReminders(reminders: ReminderEntity[]): void {
    this.save(STORAGE_KEYS.REMINDERS, reminders);
  }

  static getSearchDocs(): SearchDocumentEntity[] {
    return this.load<SearchDocumentEntity[]>(STORAGE_KEYS.SEARCH_DOCS, []);
  }

  static saveSearchDocs(docs: SearchDocumentEntity[]): void {
    this.save(STORAGE_KEYS.SEARCH_DOCS, docs);
  }

  static getPinLock(): { enabled: boolean; pin: string } {
    const enabled = this.load<boolean>(STORAGE_KEYS.APP_LOCK_ENABLED, false);
    const pin = this.load<string>(STORAGE_KEYS.APP_LOCK_PIN, '1234');
    return { enabled, pin };
  }

  static savePinLock(enabled: boolean, pin: string): void {
    this.save(STORAGE_KEYS.APP_LOCK_ENABLED, enabled);
    this.save(STORAGE_KEYS.APP_LOCK_PIN, pin);
  }

  /**
   * Purges items that have been in trash for more than 30 days
   */
  static purgeExpiredTrash(): number {
    const items = this.getItems();
    const thirtyDaysAgo = Date.now() - 30 * 24 * 60 * 60 * 1000;
    const remaining = items.filter(
      item => !item.isTrash || (item.trashTimestamp && item.trashTimestamp > thirtyDaysAgo)
    );
    const purgedCount = items.length - remaining.length;
    if (purgedCount > 0) {
      this.saveItems(remaining);
    }
    return purgedCount;
  }

  /**
   * Calculates realistic storage footprint
   */
  static computeStorageStats(): StorageStats {
    const items = this.getItems();
    const active = items.filter(i => !i.isTrash);
    const trash = items.filter(i => i.isTrash);

    const originalsBytes = active.reduce((acc, i) => acc + (i.fileSizeBytes || 1024), 0);
    const trashBytes = trash.reduce((acc, i) => acc + (i.fileSizeBytes || 1024), 0);
    const cacheBytes = Math.floor(originalsBytes * 0.15) + 512000; // Realistic cache estimate

    return {
      originalsBytes,
      cacheBytes,
      trashBytes,
      totalItems: active.length,
      trashedItems: trash.length,
    };
  }
}
