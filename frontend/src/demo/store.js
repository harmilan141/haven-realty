import { stamp } from './http.js';
import { sampleImage, samplePdf } from './files.js';

const DB_NAME = 'haven-demo';
const VERSION = 2;
let memory = null;

const request = (r) => new Promise((ok, no) => { r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error); });
async function idb(mode, work) {
  const open = indexedDB.open(DB_NAME, 1);
  open.onupgradeneeded = () => open.result.createObjectStore('state');
  const db = await request(open);
  try {
    const tx = db.transaction('state', mode);
    const done = new Promise((ok, no) => { tx.oncomplete = ok; tx.onerror = tx.onabort = () => no(tx.error); });
    const result = await request(work(tx.objectStore('state')));
    await done;
    return result;
  } finally { db.close(); }
}

export async function load() {
  let data = null;
  try { data = await idb('readonly', (s) => s.get('db')); } catch { data = memory && structuredClone(memory); }
  if (data?.version === VERSION) return data;
  data = await seed();
  await save(data);
  return data;
}

export async function save(data) {
  try { await idb('readwrite', (s) => s.put(data, 'db')); } catch (e) {
    if (e?.name === 'QuotaExceededError') throw e;
    memory = structuredClone(data);
  }
}

export async function reset() {
  memory = null;
  try { await idb('readwrite', (s) => s.delete('db')); } catch {}
}

export async function hash(password, salt = crypto.randomUUID()) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${salt}:${password}`));
  return `${salt}:${Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('')}`;
}
export const passwordMatches = async (password, stored) => (await hash(password, stored.split(':')[0])) === stored;

const DEFAULTS = {
  users: { accountEnabled: true, emailVerified: false, phoneVerified: false, buyerEnabled: true, sellerEnabled: false, dateOfBirth: null, city: null, state: null, address: null, postalCode: null, preferences: null },
  tokens: { usedAt: null, attempts: 0 },
  verifications: { status: 'PENDING', rejectionReason: null, reviewNotes: null, consentGiven: false, reviewedAt: null },
  verificationDocs: { superseded: false },
  history: {}, approvals: { reason: null },
  properties: { measurementUnit: 'sq ft', parkingAvailable: false, status: 'DRAFT', rejectionReason: null, viewCount: 0 },
  images: { sortOrder: 0 }, propertyDocs: {}, wishlist: {}, recent: {},
  enquiries: { status: 'NEW', priority: 'NORMAL', followUpDate: null, closureReason: null },
  buyerMessages: { internalNote: false }, sellerMessages: { propertyId: null },
  visits: { status: 'SCHEDULED', notes: null }, offers: { status: 'OPEN', note: null },
  rentals: { enquiryId: null, renewalDate: null, status: 'ACTIVE', notes: null },
  sales: { enquiryId: null, registryDate: null, downPayment: 0, totalCommission: 0, advanceReceived: false, balanceReceived: false, status: 'ONGOING', notes: null },
  notifications: { body: null, link: null, read: false }, audit: { details: null },
};
const TIMESTAMPS = { verifications: ['submittedAt', 'updatedAt'], verificationDocs: ['uploadedAt'], propertyDocs: ['uploadedAt'], properties: ['createdAt', 'updatedAt'], enquiries: ['createdAt', 'updatedAt'], recent: ['viewedAt'] };

export function insert(db, table, values) {
  const now = stamp();
  const times = Object.fromEntries((TIMESTAMPS[table] || ['createdAt']).map((k) => [k, now]));
  const row = { ...DEFAULTS[table], ...times, ...values, id: (db.seq[table] = (db.seq[table] || 0) + 1) };
  db[table].push(row);
  return row;
}

export function storeFile(db, { name, type, blob }) {
  const key = crypto.randomUUID();
  db.files[key] = blob;
  return { storedName: key, originalName: name, contentType: type, size: blob.size };
}

async function seed() {
  const data = (await import('./sample-data.json')).default;
  const db = { version: VERSION, seq: {}, files: {} };
  for (const t of Object.keys(DEFAULTS)) db[t] = [];

  const now = Date.now();
  const ago = (days) => stamp(new Date(now - days * 86400000));
  const day = (offset) => ago(-offset).slice(0, 10);
  const pdf = (label, name) => storeFile(db, { name, type: 'application/pdf', blob: samplePdf(label) });

  const agent = insert(db, 'users', { fullName: 'Aarav Kapoor', email: 'agent@haven.test', password: await hash('Agent@12345'), phone: '9000000000', city: 'Chandigarh', state: 'Punjab', role: 'AGENT', emailVerified: true, phoneVerified: true, buyerEnabled: false });
  const password = await hash('Demo@12345');
  const ids = { agent: agent.id };
  for (const u of data.users) {
    ids[u.email] = insert(db, 'users', {
      fullName: u.fullName, email: u.email, password, phone: u.phone, address: u.address, city: u.city, state: u.state, postalCode: u.postalCode,
      dateOfBirth: u.dob, role: 'CUSTOMER', emailVerified: u.emailVerified, phoneVerified: u.phoneVerified, sellerEnabled: u.sellerEnabled,
      accountEnabled: u.accountEnabled, preferences: u.preferences, createdAt: ago(u.createdDaysAgo),
    }).id;
  }

  const verificationIds = data.verifications.map((v) => {
    const reviewed = v.reviewedDaysAgo ?? null;
    const row = insert(db, 'verifications', {
      userId: ids[v.user], status: v.status, rejectionReason: v.rejectionReason ?? null, reviewNotes: v.reviewNotes ?? null, consentGiven: true,
      submittedAt: ago(v.submittedDaysAgo), reviewedAt: reviewed === null ? null : ago(reviewed), updatedAt: ago(reviewed ?? v.submittedDaysAgo),
    });
    for (const docType of v.docs) insert(db, 'verificationDocs', { verificationId: row.id, docType, uploadedAt: row.submittedAt, ...pdf(`${docType.toLowerCase()} proof`, `${docType.toLowerCase()}.pdf`) });
    for (const h of v.history) insert(db, 'history', { verificationId: row.id, fromStatus: h.from, toStatus: h.to, note: h.note, actorId: h.to === 'PENDING' ? row.userId : agent.id, createdAt: ago(h.daysAgo) });
    return row.id;
  });

  const props = data.properties.map((p) => {
    const created = ago(p.createdDaysAgo);
    const row = insert(db, 'properties', {
      sellerId: ids[p.seller], title: p.title, propertyType: p.type, category: p.category, description: p.description, city: p.city, locality: p.locality,
      address: p.address, postalCode: p.postalCode, area: p.area, measurementUnit: p.unit, price: p.price, bedrooms: p.bedrooms, bathrooms: p.bathrooms,
      furnishingStatus: p.furnishing, parkingAvailable: p.parking, amenities: p.amenities, facing: p.facing, approvedBy: p.approvedBy,
      constructionType: p.construction, status: p.status, rejectionReason: p.rejectionReason, viewCount: p.viewCount, createdAt: created, updatedAt: created,
    });
    for (let i = 0; i < 2; i++) insert(db, 'images', { propertyId: row.id, sortOrder: i, ...storeFile(db, { name: `photo${i + 1}.svg`, type: 'image/svg+xml', blob: sampleImage(p.title, `${p.locality}, ${p.city}`, p.colour, i) }) });
    insert(db, 'propertyDocs', { propertyId: row.id, docType: 'OWNERSHIP', uploadedAt: created, ...pdf('Ownership proof', 'ownership.pdf') });
    for (const a of p.approvals) insert(db, 'approvals', { propertyId: row.id, decision: a.decision, reason: a.reason, actorId: agent.id, createdAt: ago(a.daysAgo) });
    return row;
  });

  const enquiries = data.enquiries.map((e) => {
    const customerId = ids[e.customer];
    const updated = Math.min(e.createdDaysAgo, ...e.messages.map((m) => m.daysAgo));
    const row = insert(db, 'enquiries', {
      propertyId: props[e.property].id, customerId, enquiryType: e.type, message: e.message, status: e.status, priority: e.priority,
      closureReason: e.closureReason, followUpDate: e.followUpInDays === null ? null : day(e.followUpInDays), createdAt: ago(e.createdDaysAgo), updatedAt: ago(updated),
    });
    for (const m of e.messages) {
      const senderId = m.role === 'AGENT' ? agent.id : m.role === 'CUSTOMER' ? customerId : 0;
      insert(db, 'buyerMessages', { enquiryId: row.id, senderRole: m.role, senderId, body: m.body, internalNote: !!m.internal, createdAt: ago(m.daysAgo) });
    }
    for (const v of e.visits) insert(db, 'visits', { enquiryId: row.id, scheduledAt: `${day(v.inDays)}T${String(v.hour).padStart(2, '0')}:00:00`, status: v.status, notes: v.notes, createdAt: row.createdAt });
    for (const o of e.offers) insert(db, 'offers', { enquiryId: row.id, amount: o.amount, note: o.note, status: o.status, createdAt: ago(o.daysAgo) });
    return row;
  });

  const sales = data.sales.map((s) => {
    const e = enquiries[s.enquiry];
    const p = props.find((x) => x.id === e.propertyId);
    const registryDate = s.registryDaysAgo != null ? day(-s.registryDaysAgo) : s.registryInDays != null ? day(s.registryInDays) : null;
    return insert(db, 'sales', {
      enquiryId: e.id, propertyId: p.id, buyerId: e.customerId, sellerId: p.sellerId, finalAmount: s.finalAmount, downPayment: s.downPayment,
      dealDate: day(-s.dealDaysAgo), registryDate, totalCommission: s.commission, advanceReceived: s.advance, balanceReceived: s.balance,
      status: s.status, notes: s.notes, createdAt: ago(s.dealDaysAgo),
    });
  });
  const rentals = data.rentals.map((r) => {
    const e = enquiries[r.enquiry];
    const start = -r.startDaysAgo;
    return insert(db, 'rentals', {
      enquiryId: e.id, propertyId: e.propertyId, tenantId: e.customerId, rentAmount: r.rent, securityDeposit: r.deposit, startDate: day(start),
      endDate: day(start + r.lengthDays), renewalDate: day(start + r.lengthDays - r.renewalDaysBeforeEnd), status: r.status, notes: r.notes, createdAt: ago(r.startDaysAgo),
    });
  });

  for (const [email, i, d] of data.wishlist) insert(db, 'wishlist', { userId: ids[email], propertyId: props[i].id, createdAt: ago(d) });
  for (const [email, i, d] of data.recent) insert(db, 'recent', { userId: ids[email], propertyId: props[i].id, viewedAt: ago(d) });
  for (const m of data.sellerMessages) {
    const seller = ids[m.seller];
    const fromSeller = m.role === 'CUSTOMER';
    insert(db, 'sellerMessages', {
      sellerId: seller, propertyId: m.property === null ? null : props[m.property].id, senderRole: fromSeller ? 'SELLER' : 'AGENT',
      senderId: fromSeller ? seller : agent.id, body: m.body, createdAt: ago(m.daysAgo),
    });
  }
  for (const n of data.notifications) insert(db, 'notifications', { userId: ids[n.user], title: n.title, body: n.body, link: n.link, read: n.read, createdAt: ago(n.daysAgo) });
  const refs = { user: (v) => ids[v], verification: (v) => verificationIds[v], property: (v) => props[v].id, enquiry: (v) => enquiries[v].id, sale: (v) => sales[v].id, rental: (v) => rentals[v].id };
  for (const a of data.audit) insert(db, 'audit', { actorId: ids[a.actor], action: a.action, entityType: a.entity, entityId: refs[a.ref[0]](a.ref[1]), details: a.details, createdAt: ago(a.daysAgo) });
  return db;
}
