export type ItemKind =
  | 'PHOTO'
  | 'STICKER'
  | 'VIDEO'
  | 'AUDIO'
  | 'TEXT'
  | 'LINK'
  | 'DOCUMENT'
  | 'FILE';

export const ITEM_KIND_LABELS: Record<ItemKind, string> = {
  PHOTO: 'Photos',
  STICKER: 'Stickers',
  VIDEO: 'Videos',
  AUDIO: 'Audio',
  TEXT: 'Notes & Text',
  LINK: 'Links',
  DOCUMENT: 'Documents',
  FILE: 'Other Files',
};

export type SortOrder =
  | 'SAVED_NEWEST'
  | 'SAVED_OLDEST'
  | 'SOURCE_NEWEST'
  | 'TITLE_AZ';

export const SORT_ORDER_LABELS: Record<SortOrder, string> = {
  SAVED_NEWEST: 'Newest Saved',
  SAVED_OLDEST: 'Oldest Saved',
  SOURCE_NEWEST: 'Source Date',
  TITLE_AZ: 'Title (A-Z)',
};

export type DateFilter =
  | 'ALL_TIME'
  | 'THIS_MONTH'
  | 'THIS_YEAR'
  | 'CUSTOM';

export const DATE_FILTER_LABELS: Record<DateFilter, string> = {
  ALL_TIME: 'All Time',
  THIS_MONTH: 'This Month',
  THIS_YEAR: 'This Year',
  CUSTOM: 'Custom Range',
};

export interface ItemEntity {
  id: string;
  title: string;
  content: string;
  kind: ItemKind;
  isFavorite: boolean;
  isTrash: boolean;
  trashTimestamp?: number | null;
  createdAt: number;
  sourceDate?: number | null;
  reminderTime?: number | null;
  isReminderDone?: boolean;
  contentHash?: string | null;
  originalFileName?: string | null;
  mimeType?: string | null;
  fileSizeBytes: number;
  aiDescription?: string | null;
  aiConcepts?: string | null;
  aiStatus: 'PENDING' | 'READY' | 'FAILED';
  fileData?: string; // base64 / blob URL for local media display
  extractedUrl?: string | null;
}

export interface CollectionEntity {
  id: string;
  name: string;
  iconName: string;
  colorHex: string;
  createdAt: number;
}

export interface ItemCollectionCrossRef {
  itemId: string;
  collectionId: string;
}

export interface ItemWithCollections {
  item: ItemEntity;
  collections: CollectionEntity[];
}

export interface ReminderEntity {
  id: string;
  itemId: string;
  title: string;
  dueMillis: number;
  isDone: boolean;
  isExact: boolean;
}

export interface SearchDocumentEntity {
  id: string;
  itemId: string;
  chunkIndex: number;
  pageNumber?: number | null;
  textContent: string;
  embedding?: string | null; // comma-separated floats
  embeddingDimension: number;
  modelVersion: string;
  coverage: string;
}

export interface StorageStats {
  originalsBytes: number;
  cacheBytes: number;
  trashBytes: number;
  totalItems: number;
  trashedItems: number;
}

export interface SemanticSearchResult {
  item: ItemWithCollections;
  explanation: string;
  score: number;
}

export interface SharePayload {
  text?: string;
  uris?: string[];
  mimeType?: string;
  files?: File[];
}

export type AppDestination = 'LIBRARY' | 'COLLECTIONS' | 'SEARCH' | 'SETTINGS' | 'TRASH';
