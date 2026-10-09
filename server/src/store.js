// Contadores de uso y estado del servicio. Con Firestore sobreviven a los reinicios y se comparten entre
// instancias; sin él (pruebas locales) se guardan en memoria.

export class MemoryStore {
  constructor() {
    this.data = new Map();
  }

  async get(key) {
    return this.data.get(key) ?? null;
  }

  async set(key, value) {
    this.data.set(key, value);
  }

  /** Suma `fields` a los contadores del documento y devuelve el resultado. */
  async increment(key, fields) {
    const current = this.data.get(key) ?? {};
    const next = { ...current };
    for (const [name, amount] of Object.entries(fields)) next[name] = (next[name] ?? 0) + amount;
    this.data.set(key, next);
    return next;
  }
}

export async function createStore() {
  if (process.env.STORE === "memory") return new MemoryStore();
  try {
    const { Firestore, FieldValue } = await import("@google-cloud/firestore");
    const db = new Firestore();
    const collection = process.env.FIRESTORE_COLLECTION || "crea_reader_ai";
    const doc = (key) => db.collection(collection).doc(key.replaceAll("/", "_"));
    return {
      async get(key) {
        const snapshot = await doc(key).get();
        return snapshot.exists ? snapshot.data() : null;
      },
      async set(key, value) {
        await doc(key).set(value);
      },
      async increment(key, fields) {
        const updates = {};
        for (const [name, amount] of Object.entries(fields)) updates[name] = FieldValue.increment(amount);
        await doc(key).set(updates, { merge: true });
        return (await doc(key).get()).data();
      },
    };
  } catch (error) {
    console.warn("Firestore no disponible, se usa memoria (los contadores se pierden al reiniciar):", error.message);
    return new MemoryStore();
  }
}
