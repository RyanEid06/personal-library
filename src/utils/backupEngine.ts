import { ItemEntity, CollectionEntity, SearchDocumentEntity, ItemCollectionCrossRef } from '../types';

export interface BackupPayload {
  version: number;
  exportedAt: number;
  items: ItemEntity[];
  collections: CollectionEntity[];
  crossRefs: ItemCollectionCrossRef[];
  searchDocs: SearchDocumentEntity[];
  manifestHash: string;
}

export interface RestorePreview {
  itemCount: number;
  collectionCount: number;
  photoCount: number;
  documentCount: number;
  linkCount: number;
  payload: BackupPayload;
}

async function deriveKey(passphrase: string, salt: Uint8Array): Promise<CryptoKey> {
  const enc = new TextEncoder();
  const keyMaterial = await window.crypto.subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  );

  return window.crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt,
      iterations: 100000,
      hash: 'SHA-256',
    },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

function bufferToHex(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

function hexToBuffer(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < hex.length; i += 2) {
    bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
  }
  return bytes;
}

export const EncryptedBackupEngine = {
  async exportBackup(
    items: ItemEntity[],
    collections: CollectionEntity[],
    crossRefs: ItemCollectionCrossRef[],
    searchDocs: SearchDocumentEntity[],
    passphrase?: string
  ): Promise<string> {
    const rawData = {
      version: 1,
      exportedAt: Date.now(),
      items,
      collections,
      crossRefs,
      searchDocs,
      manifestHash: ''
    };

    const jsonStr = JSON.stringify(rawData);
    const enc = new TextEncoder();
    const digest = await window.crypto.subtle.digest('SHA-256', enc.encode(jsonStr));
    rawData.manifestHash = bufferToHex(digest);

    const completeJson = JSON.stringify(rawData);

    if (!passphrase || !passphrase.trim()) {
      // Unencrypted export (standard JSON)
      return JSON.stringify({ encrypted: false, data: completeJson }, null, 2);
    }

    // Encrypt with AES-GCM
    const salt = window.crypto.getRandomValues(new Uint8Array(16));
    const iv = window.crypto.getRandomValues(new Uint8Array(12));
    const key = await deriveKey(passphrase, salt);

    const ciphertext = await window.crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      key,
      enc.encode(completeJson)
    );

    const exportWrapper = {
      encrypted: true,
      salt: bufferToHex(salt),
      iv: bufferToHex(iv),
      ciphertext: bufferToHex(ciphertext),
    };

    return JSON.stringify(exportWrapper, null, 2);
  },

  async inspectBackup(fileContent: string, passphrase?: string): Promise<RestorePreview> {
    const wrapper = JSON.parse(fileContent);
    let payloadStr = '';

    if (!wrapper.encrypted) {
      payloadStr = typeof wrapper.data === 'string' ? wrapper.data : JSON.stringify(wrapper.data);
    } else {
      if (!passphrase) {
        throw new Error('This backup is encrypted. Please enter the passphrase.');
      }
      try {
        const salt = hexToBuffer(wrapper.salt);
        const iv = hexToBuffer(wrapper.iv);
        const ciphertext = hexToBuffer(wrapper.ciphertext);
        const key = await deriveKey(passphrase, salt);

        const decrypted = await window.crypto.subtle.decrypt(
          { name: 'AES-GCM', iv },
          key,
          ciphertext
        );

        const dec = new TextDecoder();
        payloadStr = dec.decode(decrypted);
      } catch {
        throw new Error('Decryption failed. Incorrect passphrase or corrupted backup.');
      }
    }

    const payload: BackupPayload = JSON.parse(payloadStr);

    const photoCount = payload.items.filter(i => i.kind === 'PHOTO' || i.kind === 'STICKER').length;
    const documentCount = payload.items.filter(i => i.kind === 'DOCUMENT' || i.kind === 'TEXT').length;
    const linkCount = payload.items.filter(i => i.kind === 'LINK').length;

    return {
      itemCount: payload.items.length,
      collectionCount: payload.collections.length,
      photoCount,
      documentCount,
      linkCount,
      payload,
    };
  }
};
