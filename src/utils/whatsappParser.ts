import { ItemKind } from '../types';

export type DateOrder = 'DAY_FIRST' | 'MONTH_FIRST';

export interface ParsedMessage {
  importKey: string;
  rawText: string;
  sender: string | null;
  rawDateText: string;
  parsedTimestamp: number | null;
  isDateAmbiguous: boolean;
  isSystemMessage: boolean;
  attachmentFileName: string | null;
  isMediaOmitted: boolean;
  kind: ItemKind;
  extractedUrl: string | null;
}

export interface WhatsAppParseResult {
  totalMessages: Int32Array | number;
  validDateCount: number;
  ambiguousDateCount: number;
  unparseableDateCount: number;
  mediaReferencesCount: number;
  systemMessageCount: number;
  messages: ParsedMessage[];
}

const URL_REGEX = /https?:\/\/[\w\d:#@%/;$()~_?+\-=\\.&]+/gi;
const HEADER_BRACKET_REGEX = /^\[(\d{1,4}[/\-.]\d{1,2}[/\-.]\d{2,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s+[AP]M)?)\]\s*(?:([^:]+):)?\s*(.*)$/i;
const HEADER_DASH_REGEX = /^(\d{1,4}[/\-.]\d{1,2}[/\-.]\d{2,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s+[AP]M)?)\s*-\s*(?:([^:]+):)?\s*(.*)$/i;

function classifyFileName(fileName?: string | null): ItemKind {
  if (!fileName) return 'FILE';
  const ext = fileName.split('.').pop()?.toLowerCase() || '';
  if (['jpg', 'jpeg', 'png', 'webp', 'heic'].includes(ext)) return 'PHOTO';
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return 'VIDEO';
  if (['mp3', 'opus', 'm4a', 'wav', 'ogg', 'aac'].includes(ext)) return 'AUDIO';
  if (['pdf', 'docx', 'doc', 'txt', 'csv', 'xlsx'].includes(ext)) return 'DOCUMENT';
  return 'FILE';
}

function parseDateSafely(
  rawDate: string,
  specifiedOrder?: DateOrder
): { timestamp: number | null; isAmbiguous: boolean } {
  if (!rawDate.trim()) return { timestamp: null, isAmbiguous: false };

  const clean = rawDate.replace(/[[\],]/g, ' ').trim();
  const parts = clean.split(/\s+/);
  if (parts.length === 0) return { timestamp: null, isAmbiguous: false };

  const datePart = parts[0];
  const timePart = clean.substring(datePart.length).trim();

  const dateNumbers = datePart.split(/[/\\-.]/).map(n => parseInt(n, 10)).filter(n => !isNaN(n));
  if (dateNumbers.length !== 3) return { timestamp: null, isAmbiguous: false };

  const [first, second, third] = dateNumbers;
  let year = 2026;
  let num1 = first;
  let num2 = second;

  if (third >= 100) {
    year = third;
    num1 = first;
    num2 = second;
  } else if (third >= 20 && third <= 99) {
    year = 2000 + third;
    num1 = first;
    num2 = second;
  } else if (first >= 1000) {
    year = first;
    num1 = second;
    num2 = third;
  } else {
    return { timestamp: null, isAmbiguous: false };
  }

  let month = 1;
  let day = 1;

  if (num1 <= 12 && num2 <= 12 && num1 !== num2) {
    if (!specifiedOrder) {
      // Ambiguous date - never guess
      return { timestamp: null, isAmbiguous: true };
    }
    if (specifiedOrder === 'DAY_FIRST') {
      day = num1;
      month = num2;
    } else {
      month = num1;
      day = num2;
    }
  } else if (num1 > 12 && num2 <= 12) {
    day = num1;
    month = num2;
  } else if (num2 > 12 && num1 <= 12) {
    month = num1;
    day = num2;
  } else {
    return { timestamp: null, isAmbiguous: false };
  }

  const timeTokens = parseTimePart(timePart);
  if (!timeTokens) return { timestamp: null, isAmbiguous: false };

  try {
    const d = new Date(year, month - 1, day, timeTokens.hour, timeTokens.minute, timeTokens.second);
    return { timestamp: d.getTime(), isAmbiguous: false };
  } catch {
    return { timestamp: null, isAmbiguous: false };
  }
}

function parseTimePart(timePart: string): { hour: number; minute: number; second: number } | null {
  if (!timePart.trim()) return { hour: 0, minute: 0, second: 0 };
  const upper = timePart.toUpperCase().trim();
  const isPm = upper.includes('PM');
  const isAm = upper.includes('AM');
  const cleanTime = upper.replace(/AM|PM/g, '').trim();
  const tokens = cleanTime.split(':').map(t => parseInt(t.trim(), 10));
  if (tokens.length < 2 || isNaN(tokens[0]) || isNaN(tokens[1])) return null;

  let hour = tokens[0];
  const minute = tokens[1];
  const second = tokens.length > 2 && !isNaN(tokens[2]) ? tokens[2] : 0;

  if (isPm && hour < 12) hour += 12;
  if (isAm && hour === 12) hour = 0;

  return { hour, minute, second };
}

function simpleHash(str: string): string {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash |= 0;
  }
  return 'wa_' + Math.abs(hash).toString(16);
}

export const WhatsAppExportParser = {
  parse(chatText: string, dateOrder?: DateOrder): WhatsAppParseResult {
    const lines = chatText.split(/\r?\n/);
    const parsedMessages: ParsedMessage[] = [];

    let currentRawDate = '';
    let currentSender: string | null = null;
    let isCurrentSystem = false;
    let currentBodyLines: string[] = [];

    function flushMessage() {
      const body = currentBodyLines.join('\n').trim();
      if (body || currentRawDate) {
        const isOmitted = /<Media omitted>/i.test(body);
        const isAttached = /\(file attached\)|<attached:/i.test(body);
        const isMedia = isOmitted || isAttached;

        let attachmentFileName: string | null = null;
        if (isMedia) {
          const match = body.match(/([a-zA-Z0-9_\-.]+\.(?:jpg|jpeg|png|webp|mp4|mov|mp3|opus|m4a|wav|pdf|docx|txt|zip))/i);
          if (match) attachmentFileName = match[1];
        }

        const urlMatch = body.match(URL_REGEX);
        const extractedUrl = urlMatch ? urlMatch[0] : null;

        let kind: ItemKind = 'TEXT';
        if (extractedUrl) {
          kind = 'LINK';
        } else if (isMedia) {
          kind = classifyFileName(attachmentFileName);
        }

        const { timestamp, isAmbiguous } = parseDateSafely(currentRawDate, dateOrder);
        const keyInput = `${currentSender || ''}_${currentRawDate}_${body}`;
        const importKey = simpleHash(keyInput);

        parsedMessages.push({
          importKey,
          rawText: body,
          sender: currentSender,
          rawDateText: currentRawDate,
          parsedTimestamp: timestamp,
          isDateAmbiguous: isAmbiguous,
          isSystemMessage: isCurrentSystem,
          attachmentFileName,
          isMediaOmitted: isOmitted,
          kind,
          extractedUrl,
        });
      }

      currentBodyLines = [];
      currentRawDate = '';
      currentSender = null;
      isCurrentSystem = false;
    }

    for (const line of lines) {
      const bracketMatch = line.match(HEADER_BRACKET_REGEX);
      const dashMatch = !bracketMatch ? line.match(HEADER_DASH_REGEX) : null;
      const match = bracketMatch || dashMatch;

      if (match) {
        flushMessage();
        const datePart = (match[1] || '').trim();
        const timePart = (match[2] || '').trim();
        currentRawDate = `${datePart} ${timePart}`;

        const rawSender = (match[3] || '').trim();
        const messageText = match[4] || '';

        if (
          !rawSender ||
          rawSender.includes('Messages and calls are end-to-end encrypted') ||
          rawSender.includes('created group')
        ) {
          isCurrentSystem = true;
          currentSender = null;
          currentBodyLines.push(rawSender ? `${rawSender}: ${messageText}` : messageText);
        } else {
          isCurrentSystem = false;
          currentSender = rawSender;
          currentBodyLines.push(messageText);
        }
      } else {
        if (currentBodyLines.length > 0) {
          currentBodyLines.push(line);
        } else if (line.trim().length > 0) {
          currentBodyLines.push(line);
        }
      }
    }

    flushMessage();

    const validCount = parsedMessages.filter(m => m.parsedTimestamp !== null && !m.isDateAmbiguous).length;
    const ambiguousCount = parsedMessages.filter(m => m.isDateAmbiguous).length;
    const unparseableCount = parsedMessages.filter(m => m.parsedTimestamp === null && !m.isDateAmbiguous).length;
    const mediaCount = parsedMessages.filter(m => m.attachmentFileName !== null || m.isMediaOmitted).length;
    const systemCount = parsedMessages.filter(m => m.isSystemMessage).length;

    return {
      totalMessages: parsedMessages.length,
      validDateCount: validCount,
      ambiguousDateCount: ambiguousCount,
      unparseableDateCount: unparseableCount,
      mediaReferencesCount: mediaCount,
      systemMessageCount: systemCount,
      messages: parsedMessages,
    };
  }
};
