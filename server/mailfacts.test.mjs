import assert from 'node:assert/strict';
import { extract, digestLine } from './mailfacts.mjs';

const BASE_DATE = '2026-11-24T10:00:00-08:00';
const mail = (overrides) => ({
  from: 'Acme <no-reply@acme.example.com>',
  to: 'pat@example.com',
  subject: '',
  date: BASE_DATE,
  text: '',
  html: '',
  attachments: [],
  ...overrides,
});

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

// --- 1. JSON-LD fixtures ---

test('jsonld: Order confirmed', () => {
  const html = `<script type="application/ld+json">${JSON.stringify({
    '@context': 'http://schema.org',
    '@type': 'Order',
    merchant: { '@type': 'Organization', name: 'Northwind Supply' },
    orderNumber: 'NW-90412',
    priceCurrency: 'USD',
    price: '129.50',
    orderStatus: 'http://schema.org/OrderProcessing',
    acceptedOffer: { '@type': 'Offer', itemOffered: { '@type': 'Product', name: 'Trail Running Shoes' } },
  })}</script>`;
  const [it] = extract(mail({ subject: 'Order received', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'order');
  assert.equal(it.who, 'Northwind Supply');
  assert.deepEqual(it.amount, { value: 129.5, currency: 'USD' });
  assert.equal(it.ref, 'NW-90412');
  assert.equal(it.calendar, false);
  assert.match(it.title, /Trail Running Shoes/);
});

test('jsonld: Order cancelled', () => {
  const html = `<script type="application/ld+json">${JSON.stringify({
    '@context': 'http://schema.org',
    '@type': 'Order',
    merchant: { '@type': 'Organization', name: 'Northwind Supply' },
    orderNumber: 'NW-90412',
    orderStatus: 'http://schema.org/OrderCancelled',
    acceptedOffer: [{ '@type': 'Offer', itemOffered: { '@type': 'Product', name: 'Trail Running Shoes' } }],
  })}</script>`;
  const [it] = extract(mail({ subject: 'Order cancelled', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'cancelled');
  assert.equal(it.ref, 'NW-90412');
  assert.equal(it.calendar, false);
});

test('jsonld: FlightReservation inside @graph', () => {
  const html = `<script type="application/ld+json">${JSON.stringify({
    '@context': 'https://schema.org',
    '@graph': [{
      '@type': 'FlightReservation',
      reservationNumber: 'K7M9QX',
      reservationStatus: 'https://schema.org/ReservationConfirmed',
      reservationFor: {
        '@type': 'Flight',
        flightNumber: '412',
        airline: { '@type': 'Airline', name: 'Pacific Air', iataCode: 'PA' },
        departureAirport: { '@type': 'Airport', iataCode: 'SFO' },
        arrivalAirport: { '@type': 'Airport', iataCode: 'JFK' },
        departureTime: '2026-12-10T08:15:00-08:00',
        arrivalTime: '2026-12-10T16:50:00-05:00',
      },
    }],
  })}</script>`;
  const [it] = extract(mail({ subject: 'Your flight', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'flight');
  assert.equal(it.who, 'Pacific Air');
  assert.equal(it.when, '2026-12-10T08:15:00-08:00');
  assert.equal(it.end, '2026-12-10T16:50:00-05:00');
  assert.equal(it.where, 'SFO to JFK');
  assert.equal(it.ref, 'K7M9QX');
  assert.equal(it.calendar, true);
});

test('jsonld: FoodEstablishmentReservation', () => {
  const html = `<script type="application/ld+json">${JSON.stringify({
    '@context': 'http://schema.org',
    '@type': 'FoodEstablishmentReservation',
    reservationNumber: 'RES-7741',
    reservationStatus: 'http://schema.org/ReservationConfirmed',
    startTime: '2026-12-04T19:30:00-08:00',
    partySize: 2,
    reservationFor: {
      '@type': 'FoodEstablishment',
      name: 'Bistro Verde',
      address: { streetAddress: '410 Oak Ave', addressLocality: 'Portland', addressRegion: 'OR' },
    },
  })}</script>`;
  const [it] = extract(mail({ subject: 'Table confirmed', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'reservation');
  assert.equal(it.who, 'Bistro Verde');
  assert.equal(it.when, '2026-12-04T19:30:00-08:00');
  assert.equal(it.where, '410 Oak Ave, Portland, OR');
  assert.equal(it.ref, 'RES-7741');
  assert.equal(it.calendar, true);
});

test('jsonld: LodgingReservation', () => {
  const html = `<script type="application/ld+json">${JSON.stringify([{
    '@type': 'LodgingReservation',
    reservationNumber: 'HTL-3310',
    checkinTime: '2026-12-14T15:00:00-08:00',
    checkoutTime: '2026-12-16T11:00:00-08:00',
    totalPrice: 420,
    priceCurrency: 'USD',
    reservationFor: {
      '@type': 'LodgingBusiness',
      name: 'Cedar Inn',
      address: { streetAddress: '88 Pine St', addressLocality: 'Seattle' },
    },
  }])}</script>`;
  const [it] = extract(mail({ subject: 'Stay confirmed', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'hotel');
  assert.equal(it.who, 'Cedar Inn');
  assert.equal(it.when, '2026-12-14T15:00:00-08:00');
  assert.equal(it.end, '2026-12-16T11:00:00-08:00');
  assert.deepEqual(it.amount, { value: 420, currency: 'USD' });
  assert.equal(it.calendar, true);
});

test('jsonld: EventReservation', () => {
  const html = `<script type="application/ld+json">${JSON.stringify({
    '@type': 'EventReservation',
    reservationNumber: 'TKT-5590',
    reservationFor: {
      '@type': 'Event',
      name: 'Winter Quartet Concert',
      startDate: '2026-12-18T20:00:00-08:00',
      location: { '@type': 'Place', name: 'Civic Hall', address: { addressLocality: 'Portland' } },
      organizer: { '@type': 'Organization', name: 'City Arts' },
    },
  })}</script>`;
  const [it] = extract(mail({ subject: 'Your tickets', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'event');
  assert.equal(it.title, 'Winter Quartet Concert');
  assert.equal(it.who, 'City Arts');
  assert.equal(it.calendar, true);
});

test('jsonld: Invoice PaymentDue -> bill_due all-day calendar', () => {
  const html = `<script type="application/ld+json">${JSON.stringify({
    '@type': 'Invoice',
    provider: { '@type': 'Organization', name: 'Metro Electric' },
    accountId: 'ACCT-901',
    paymentStatus: 'http://schema.org/PaymentDue',
    paymentDueDate: '2026-12-15T00:00:00Z',
    totalPaymentDue: { '@type': 'PriceSpecification', price: 84.25, priceCurrency: 'USD' },
  })}</script>`;
  const [it] = extract(mail({ subject: 'Monthly bill', html }));
  assert.equal(it.source, 'jsonld');
  assert.equal(it.kind, 'bill_due');
  assert.equal(it.when, '2026-12-15');
  assert.deepEqual(it.amount, { value: 84.25, currency: 'USD' });
  assert.equal(it.calendar, true);
});

test('jsonld: RentalCarReservation and TrainReservation and BusReservation', () => {
  const html = `<script type="application/ld+json">${JSON.stringify([
    {
      '@type': 'RentalCarReservation',
      reservationNumber: 'CAR-101',
      provider: { name: 'Clearway Rentals' },
      pickupTime: '2026-12-20T10:00:00-08:00',
      dropoffTime: '2026-12-22T10:00:00-08:00',
      pickupLocation: { name: 'SFO Terminal' },
    },
    {
      '@type': 'TrainReservation',
      reservationNumber: 'TRN-202',
      reservationFor: {
        provider: { name: 'Coast Rail' },
        departureStation: { name: 'Portland Union' },
        arrivalStation: { name: 'Seattle King St' },
        departureTime: '2026-12-21T09:00:00-08:00',
      },
    },
    {
      '@type': 'BusReservation',
      reservationNumber: 'BUS-303',
      reservationStatus: 'http://schema.org/ReservationCancelled',
      reservationFor: {
        provider: { name: 'Valley Coach' },
        departureBusStop: { name: 'Downtown' },
        arrivalBusStop: { name: 'Airport' },
        departureTime: '2026-12-23T07:00:00-08:00',
      },
    },
  ])}</script>`;
  const items = extract(mail({ html }));
  assert.equal(items.length, 3);
  assert.equal(items[0].kind, 'reservation');
  assert.equal(items[0].calendar, true);
  assert.equal(items[1].kind, 'reservation');
  assert.equal(items[1].where, 'Portland Union to Seattle King St');
  assert.equal(items[2].kind, 'cancelled');
  assert.equal(items[2].calendar, false);
});

// --- 2. Microdata fixtures ---

test('microdata: ParcelDelivery delivered', () => {
  const html = `
  <div itemscope itemtype="http://schema.org/ParcelDelivery">
    <div itemprop="deliveryAddress" itemscope itemtype="http://schema.org/PostalAddress">
      <meta itemprop="streetAddress" content="742 Evergreen Ter">
      <meta itemprop="addressLocality" content="Springfield">
    </div>
    <meta itemprop="expectedArrivalUntil" content="2026-11-24T17:00:00-08:00">
    <div itemprop="carrier" itemscope itemtype="http://schema.org/Organization">
      <meta itemprop="name" content="ParcelCo">
    </div>
    <div itemprop="itemShipped" itemscope itemtype="http://schema.org/Product">
      <span itemprop="name">Running Shoes</span>
    </div>
    <meta itemprop="trackingNumber" content="1Z999AA10123456784">
    <link itemprop="deliveryStatus" href="http://schema.org/OrderDelivered">
  </div>`;
  const [it] = extract(mail({ subject: 'Delivered', html }));
  assert.equal(it.source, 'microdata');
  assert.equal(it.kind, 'delivered');
  assert.equal(it.who, 'ParcelCo');
  assert.equal(it.ref, '1Z999AA10123456784');
  assert.equal(it.where, '742 Evergreen Ter, Springfield');
  assert.match(it.title, /Running Shoes/);
  assert.equal(it.calendar, false);
});

test('microdata: FoodEstablishmentReservation with time tag', () => {
  const html = `
  <div itemscope itemtype="https://schema.org/FoodEstablishmentReservation">
    <meta itemprop="reservationNumber" content="MD-552">
    <time itemprop="startTime" datetime="2026-12-06T18:30:00-08:00">Dec 6, 6:30 PM</time>
    <div itemprop="reservationFor" itemscope itemtype="https://schema.org/FoodEstablishment">
      <span itemprop="name">Harbor Noodle</span>
    </div>
  </div>`;
  const [it] = extract(mail({ html }));
  assert.equal(it.source, 'microdata');
  assert.equal(it.kind, 'reservation');
  assert.equal(it.who, 'Harbor Noodle');
  assert.equal(it.when, '2026-12-06T18:30:00-08:00');
  assert.equal(it.calendar, true);
});

// --- 3. iCalendar (ICS) fixtures ---

test('ics: invite with folded lines and TZID', () => {
  const ics = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'METHOD:REQUEST',
    'BEGIN:VEVENT',
    'UID:evt-dentist-1001@example.com',
    'DTSTART;TZID=America/Los_Angeles:20261202T153000',
    'DTEND;TZID=America/Los_Angeles:20261202T163000',
    'SUMMARY:Dentist Cleaning and',
    '  Checkup',
    'LOCATION:Bright Smile Dental\\, 200 Main',
    '  St',
    'ORGANIZER;CN="Bright Smile Dental":mailto:desk@brightsmile.example.com',
    'END:VEVENT',
    'END:VCALENDAR',
  ].join('\r\n');
  const [it] = extract(mail({
    from: 'Bright Smile Dental <desk@brightsmile.example.com>',
    subject: 'Invitation: Dentist',
    attachments: [{ contentType: 'text/calendar', filename: 'invite.ics', content: ics }],
  }));
  assert.equal(it.source, 'ics');
  assert.equal(it.kind, 'appointment');
  assert.equal(it.title, 'Dentist Cleaning and Checkup');
  assert.equal(it.who, 'Bright Smile Dental');
  assert.equal(it.when, '2026-12-02T15:30:00-08:00');
  assert.equal(it.end, '2026-12-02T16:30:00-08:00');
  assert.equal(it.where, 'Bright Smile Dental, 200 Main St');
  assert.equal(it.ref, 'evt-dentist-1001@example.com');
  assert.equal(it.calendar, true);
});

test('ics: cancel with METHOD:CANCEL and folding', () => {
  const ics = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'METHOD:CANCEL',
    'BEGIN:VEVENT',
    'UID:evt-dentist-1001@example.com',
    'DTSTART;TZID=America/New_York:20261202T153000',
    'SUMMARY:Dentist',
    ' Checkup',
    'STATUS:CANCELLED',
    'END:VEVENT',
    'END:VCALENDAR',
  ].join('\r\n');
  const [it] = extract(mail({
    from: 'Bright Smile Dental <desk@brightsmile.example.com>',
    attachments: [{ contentType: 'text/calendar; method=CANCEL', filename: 'cancel.ics', content: ics }],
  }));
  assert.equal(it.source, 'ics');
  assert.equal(it.kind, 'cancelled');
  assert.equal(it.ref, 'evt-dentist-1001@example.com');
  assert.equal(it.when, '2026-12-02T15:30:00-05:00');
  assert.equal(it.calendar, false);
});

test('ics: inline UTC Z and all-day VALUE=DATE', () => {
  const ics = [
    'BEGIN:VCALENDAR',
    'BEGIN:VEVENT',
    'UID:allday-99',
    'DTSTART;VALUE=DATE:20261225',
    'DTEND;VALUE=DATE:20261226',
    'SUMMARY:Winter Summit Conference',
    'END:VEVENT',
    'BEGIN:VEVENT',
    'UID:utc-88',
    'DTSTART:20261203T180000Z',
    'DTEND:20261203T190000Z',
    'SUMMARY:Design Review',
    'END:VEVENT',
    'END:VCALENDAR',
  ].join('\n');
  const items = extract(mail({ text: ics }));
  assert.equal(items.length, 2);
  assert.equal(items[0].kind, 'event');
  assert.equal(items[0].when, '2026-12-25');
  assert.equal(items[0].calendar, true);
  assert.equal(items[1].kind, 'appointment');
  assert.equal(items[1].when, '2026-12-03T18:00:00Z');
  assert.equal(items[1].calendar, true);
});

// --- 4. Pattern fixtures: English (all 20 kinds) ---

test('pattern: payment_sent (Blue Bottle $42.10)', () => {
  const [it] = extract(mail({
    from: 'PayPal <service@paypal.example.com>',
    subject: 'You paid $42.10 to Blue Bottle',
    text: 'You paid $42.10 to Blue Bottle on Nov 24, 2026. Reference: PP-88210.',
  }));
  assert.equal(it.source, 'pattern');
  assert.equal(it.kind, 'payment_sent');
  assert.equal(it.who, 'Blue Bottle');
  assert.deepEqual(it.amount, { value: 42.1, currency: 'USD' });
  assert.equal(it.ref, 'PP-88210');
  assert.equal(it.calendar, false);
});

test('pattern: payment_sent with Euro comma decimal (€9,50)', () => {
  const [it] = extract(mail({
    from: 'Bank <alerts@eurobank.example.com>',
    subject: 'Payment sent',
    text: 'You sent €9,50 to Cafe Soleil. Reference: EU-4410.',
  }));
  assert.equal(it.kind, 'payment_sent');
  assert.deepEqual(it.amount, { value: 9.5, currency: 'EUR' });
  assert.equal(it.who, 'Cafe Soleil');
});

test('pattern: payment_received ($1,234.56)', () => {
  const [it] = extract(mail({
    from: 'Venmo <venmo@venmo.example.com>',
    subject: 'Jordan Lee paid you $1,234.56',
    text: 'Jordan Lee paid you $1,234.56 for November rent split.',
  }));
  assert.equal(it.kind, 'payment_received');
  assert.equal(it.who, 'Jordan Lee');
  assert.deepEqual(it.amount, { value: 1234.56, currency: 'USD' });
  assert.equal(it.calendar, false);
});

test('pattern: payment_received with USD 12 format', () => {
  const [it] = extract(mail({
    from: 'Zelle <notify@bank.example.com>',
    subject: 'You received a payment',
    text: 'You have received a payment of USD 12 from Casey Morgan.',
  }));
  assert.equal(it.kind, 'payment_received');
  assert.deepEqual(it.amount, { value: 12, currency: 'USD' });
  assert.equal(it.who, 'Casey Morgan');
});

test('pattern: payment_failed', () => {
  const [it] = extract(mail({
    from: 'StreamBox <billing@streambox.example.com>',
    subject: 'Action needed: payment failed',
    text: 'Your payment of $15.99 was declined by your card issuer. Invoice #INV-7712.',
  }));
  assert.equal(it.kind, 'payment_failed');
  assert.equal(it.who, 'StreamBox');
  assert.deepEqual(it.amount, { value: 15.99, currency: 'USD' });
  assert.equal(it.ref, 'INV-7712');
  assert.equal(it.calendar, false);
});

test('pattern: receipt from HTML-only body', () => {
  const [it] = extract(mail({
    from: 'CloudHost <receipts@cloudhost.example.com>',
    subject: 'Your receipt from CloudHost',
    text: '',
    html: '<html><body><h1>Your receipt</h1><p>Thank you for your payment of $29.00.</p><p>Receipt #RC-2091</p></body></html>',
  }));
  assert.equal(it.kind, 'receipt');
  assert.equal(it.who, 'CloudHost');
  assert.deepEqual(it.amount, { value: 29, currency: 'USD' });
  assert.equal(it.ref, 'RC-2091');
  assert.equal(it.calendar, false);
});

test('pattern: order confirmation', () => {
  const [it] = extract(mail({
    from: 'BookBarn <orders@bookbarn.example.com>',
    subject: 'Order confirmation #BB-44901',
    text: 'Thank you for your order! Order #BB-44901 total: $38.40.',
  }));
  assert.equal(it.kind, 'order');
  assert.equal(it.who, 'BookBarn');
  assert.deepEqual(it.amount, { value: 38.4, currency: 'USD' });
  assert.equal(it.ref, 'BB-44901');
  assert.equal(it.calendar, false);
});

test('pattern: shipped with tracking number', () => {
  const [it] = extract(mail({
    from: 'GearWorks <shipping@gearworks.example.com>',
    subject: 'Your order has shipped',
    text: 'Good news! Your package has shipped. Tracking number: 1Z999AA10987654321. Estimated delivery: December 1, 2026.',
  }));
  assert.equal(it.kind, 'shipped');
  assert.equal(it.ref, '1Z999AA10987654321');
  assert.equal(it.when, '2026-12-01');
  assert.equal(it.calendar, false);
});

test('pattern: out_for_delivery', () => {
  const [it] = extract(mail({
    from: 'FastShip <alerts@fastship.example.com>',
    subject: 'Out for delivery today',
    text: 'Your parcel is out for delivery. Tracking #FS-99887766.',
  }));
  assert.equal(it.kind, 'out_for_delivery');
  assert.equal(it.ref, 'FS-99887766');
  assert.equal(it.when, '2026-11-24');
  assert.equal(it.calendar, false);
});

test('pattern: delivered', () => {
  const [it] = extract(mail({
    from: 'FastShip <alerts@fastship.example.com>',
    subject: 'Delivered: Winter Jacket',
    text: 'Your package was delivered to your front porch. Tracking number: FS-11223344.',
  }));
  assert.equal(it.kind, 'delivered');
  assert.equal(it.ref, 'FS-11223344');
  assert.match(it.title, /Winter Jacket/);
  assert.equal(it.calendar, false);
});

test('pattern: refund issued', () => {
  const [it] = extract(mail({
    from: 'BookBarn <support@bookbarn.example.com>',
    subject: 'Refund issued for your order',
    text: 'We have issued a refund of $19.95 for Order #BB-44901.',
  }));
  assert.equal(it.kind, 'refund');
  assert.deepEqual(it.amount, { value: 19.95, currency: 'USD' });
  assert.equal(it.ref, 'BB-44901');
  assert.equal(it.calendar, false);
});

test('pattern: return received', () => {
  const [it] = extract(mail({
    from: 'GearWorks <returns@gearworks.example.com>',
    subject: 'Return received',
    text: 'We have received your return for Order #GW-7731.',
  }));
  assert.equal(it.kind, 'return');
  assert.equal(it.ref, 'GW-7731');
  assert.equal(it.calendar, false);
});

test('pattern: cancelled appointment', () => {
  const [it] = extract(mail({
    from: 'Bright Smile Dental <desk@brightsmile.example.com>',
    subject: 'Your appointment has been cancelled',
    text: 'Your dental appointment (Confirmation #BSD-512) has been cancelled.',
  }));
  assert.equal(it.kind, 'cancelled');
  assert.equal(it.ref, 'BSD-512');
  assert.equal(it.calendar, false);
});

test('pattern: appointment with missing year and local time (no Z)', () => {
  const [it] = extract(mail({
    from: 'Bright Smile Dental <desk@brightsmile.example.com>',
    subject: 'Dentist appointment confirmed',
    text: 'Your appointment is confirmed for Tuesday, December 2 at 3:30 PM.\nLocation: 200 Main St, Portland\nConfirmation #A1B2',
  }));
  assert.equal(it.kind, 'appointment');
  assert.equal(it.when, '2026-12-02T15:30:00');
  assert.ok(!it.when.endsWith('Z'), 'local time without zone must not end with Z');
  assert.equal(it.where, '200 Main St, Portland');
  assert.equal(it.ref, 'A1B2');
  assert.equal(it.calendar, true);
});

test('pattern: appointment year rollover (Dec mail -> Jan appointment)', () => {
  const [it] = extract(mail({
    from: 'OpticCare <care@optic.example.com>',
    date: '2026-12-20T09:00:00-08:00',
    subject: 'Eye exam appointment confirmed',
    text: 'Your appointment is confirmed for January 5 at 9:00 AM.',
  }));
  assert.equal(it.kind, 'appointment');
  assert.equal(it.when, '2027-01-05T09:00:00');
  assert.equal(it.calendar, true);
});

test('pattern: reservation with MM/DD/YYYY 24h time', () => {
  const [it] = extract(mail({
    from: 'TableBook <notify@tablebook.example.com>',
    subject: 'Booking confirmed',
    text: 'Your dinner reservation at Cedar Bistro is confirmed.\nDate: 12/02/2026 15:30\nReservation #TB-881',
  }));
  assert.equal(it.kind, 'reservation');
  assert.equal(it.who, 'Cedar Bistro');
  assert.equal(it.when, '2026-12-02T15:30:00');
  assert.equal(it.ref, 'TB-881');
  assert.equal(it.calendar, true);
});

test('pattern: flight with timezone abbreviation PST', () => {
  const [it] = extract(mail({
    from: 'SkyWays <tickets@skyways.example.com>',
    subject: 'Flight confirmation: SW 204',
    text: 'Your flight SW 204 SFO to SEA is booked.\nDeparture: Dec 2, 2026, 3:30 PM PST\nArrival: Dec 2, 2026, 5:45 PM PST\nConfirmation code: X9Q4LP',
  }));
  assert.equal(it.kind, 'flight');
  assert.equal(it.when, '2026-12-02T15:30:00-08:00');
  assert.equal(it.end, '2026-12-02T17:45:00-08:00');
  assert.equal(it.where, 'SFO to SEA');
  assert.equal(it.ref, 'X9Q4LP');
  assert.equal(it.calendar, true);
});

test('pattern: hotel check-in and check-out', () => {
  const [it] = extract(mail({
    from: 'StayNow <confirm@staynow.example.com>',
    subject: 'Hotel reservation confirmed',
    text: 'Your stay at Harbor Lodge is confirmed.\nCheck-in: Dec 12, 2026\nCheck-out: Dec 15, 2026\nBooking #HL-3044\nTotal: $510.00',
  }));
  assert.equal(it.kind, 'hotel');
  assert.equal(it.who, 'Harbor Lodge');
  assert.equal(it.when, '2026-12-12');
  assert.equal(it.end, '2026-12-15');
  assert.equal(it.ref, 'HL-3044');
  assert.deepEqual(it.amount, { value: 510, currency: 'USD' });
  assert.equal(it.calendar, true);
});

test('pattern: event tickets', () => {
  const [it] = extract(mail({
    from: 'TicketBay <orders@ticketbay.example.com>',
    subject: 'Your tickets for Autumn Jazz Night',
    text: 'Your tickets for Autumn Jazz Night are ready!\nWhen: December 8, 2026 at 7:30 PM\nVenue: Riverfront Theater\nOrder #TK-6091',
  }));
  assert.equal(it.kind, 'event');
  assert.equal(it.when, '2026-12-08T19:30:00');
  assert.equal(it.where, 'Riverfront Theater');
  assert.equal(it.ref, 'TK-6091');
  assert.equal(it.calendar, true);
});

test('pattern: bill_due all-day calendar', () => {
  const [it] = extract(mail({
    from: 'City Water <billing@citywater.example.com>',
    subject: 'Your bill is ready',
    text: 'Amount due: $62.40\nPayment due on December 18, 2026.\nInvoice #CW-202611',
  }));
  assert.equal(it.kind, 'bill_due');
  assert.deepEqual(it.amount, { value: 62.4, currency: 'USD' });
  assert.equal(it.when, '2026-12-18');
  assert.equal(it.ref, 'CW-202611');
  assert.equal(it.calendar, true);
});

test('pattern: subscription_renewal (calendar false)', () => {
  const [it] = extract(mail({
    from: 'CloudNotes <billing@cloudnotes.example.com>',
    subject: 'Upcoming renewal reminder',
    text: 'Your CloudNotes Pro subscription will renew on December 20, 2026 for $12.00.',
  }));
  assert.equal(it.kind, 'subscription_renewal');
  assert.equal(it.when, '2026-12-20');
  assert.deepEqual(it.amount, { value: 12, currency: 'USD' });
  assert.equal(it.calendar, false);
});

test('pattern: security_code NEVER leaks code in any field or digestLine', () => {
  const SECRET_CODE = '739481';
  const [it] = extract(mail({
    from: 'Acme Cloud <no-reply@acmecloud.example.com>',
    subject: `${SECRET_CODE} is your Acme Cloud sign-in code`,
    text: `Use verification code ${SECRET_CODE} to sign in. This code expires in 10 minutes.`,
  }));
  assert.equal(it.kind, 'security_code');
  assert.equal(it.calendar, false);
  assert.equal(it.ref, null);
  assert.equal(it.amount, null);
  assert.equal(it.when, null);
  const serialized = JSON.stringify(it);
  const line = digestLine(it);
  assert.ok(!serialized.includes(SECRET_CODE), 'security code must never appear in item');
  assert.ok(!line.includes(SECRET_CODE), 'security code must never appear in digestLine');
  assert.match(it.title, /^Sign-in code from /);
});

test('pattern: security_alert new sign-in', () => {
  const [it] = extract(mail({
    from: 'Acme Cloud <security@acmecloud.example.com>',
    subject: 'Security alert: New sign-in on your account',
    text: 'A new sign-in was detected from Seattle, WA.\nTime: 2026-11-24 09:42',
  }));
  assert.equal(it.kind, 'security_alert');
  assert.equal(it.where, 'Seattle, WA');
  assert.equal(it.when, '2026-11-24T09:42:00');
  assert.equal(it.calendar, false);
});

test('pattern: security_alert password changed', () => {
  const [it] = extract(mail({
    from: 'MailBox <security@mailbox.example.com>',
    subject: 'Your password was changed',
    text: 'The password for your account was changed just now. If this was not you, secure your account.',
  }));
  assert.equal(it.kind, 'security_alert');
  assert.match(it.title, /Password changed/);
  assert.equal(it.calendar, false);
});

// --- 5. Simplified Chinese fixtures ---

test('zh: payment_sent 付款成功 ¥88.00', () => {
  const [it] = extract(mail({
    from: '支付宝 <notify@alipay.example.com>',
    subject: '付款成功通知',
    text: '您已付款成功，金额 ¥88.00，商户：蓝瓶咖啡，交易单号：TX2026112401。',
  }));
  assert.equal(it.kind, 'payment_sent');
  assert.deepEqual(it.amount, { value: 88, currency: 'CNY' });
  assert.equal(it.who, '蓝瓶咖啡');
  assert.equal(it.ref, 'TX2026112401');
  assert.equal(it.calendar, false);
});

test('zh: payment_received 收款成功 88元', () => {
  const [it] = extract(mail({
    from: '微信支付 <pay@wechat.example.com>',
    subject: '收款成功提醒',
    text: '您收到一笔转账 88元，付款方：张伟。',
  }));
  assert.equal(it.kind, 'payment_received');
  assert.deepEqual(it.amount, { value: 88, currency: 'CNY' });
  assert.equal(it.who, '张伟');
});

test('zh: payment_failed 支付失败', () => {
  const [it] = extract(mail({
    from: '云盘服务 <billing@cloud.example.com>',
    subject: '支付失败通知',
    text: '您的自动扣款支付失败，金额 30元，订单号：ORD-9910。',
  }));
  assert.equal(it.kind, 'payment_failed');
  assert.deepEqual(it.amount, { value: 30, currency: 'CNY' });
  assert.equal(it.ref, 'ORD-9910');
});

test('zh: order 订单确认', () => {
  const [it] = extract(mail({
    from: '晨光书店 <orders@bookstore.example.com>',
    subject: '订单确认通知',
    text: '感谢下单，已收到您的订单，订单号：BK20261124，总金额 126.50元。',
  }));
  assert.equal(it.kind, 'order');
  assert.deepEqual(it.amount, { value: 126.5, currency: 'CNY' });
  assert.equal(it.ref, 'BK20261124');
});

test('zh: shipped / out_for_delivery / delivered', () => {
  const [s] = extract(mail({
    from: '顺达快递 <notify@shunda.example.com>',
    subject: '您的包裹已发货',
    text: '订单已发货，运单号：SF1234567890。',
  }));
  assert.equal(s.kind, 'shipped');
  assert.equal(s.ref, 'SF1234567890');

  const [o] = extract(mail({
    from: '顺达快递 <notify@shunda.example.com>',
    subject: '快件派送中',
    text: '您的包裹正在派送中，运单号：SF1234567890。',
  }));
  assert.equal(o.kind, 'out_for_delivery');

  const [d] = extract(mail({
    from: '顺达快递 <notify@shunda.example.com>',
    subject: '包裹已签收',
    text: '快件已签收，运单号：SF1234567890，签收时间：2026年11月24日 14:20。',
  }));
  assert.equal(d.kind, 'delivered');
  assert.equal(d.when, '2026-11-24T14:20:00');
});

test('zh: refund 退款成功 & cancelled 已取消', () => {
  const [r] = extract(mail({
    from: '晨光书店 <support@bookstore.example.com>',
    subject: '退款成功通知',
    text: '退款已到账，金额 ¥55.00，订单号：BK20261124。',
  }));
  assert.equal(r.kind, 'refund');
  assert.deepEqual(r.amount, { value: 55, currency: 'CNY' });

  const [c] = extract(mail({
    from: '晨光书店 <support@bookstore.example.com>',
    subject: '订单已取消',
    text: '您的订单已取消，订单号：BK20261124。',
  }));
  assert.equal(c.kind, 'cancelled');
  assert.equal(c.ref, 'BK20261124');
  assert.equal(c.calendar, false);
});

test('zh: appointment 预约成功 with 2026年12月2日 15:30', () => {
  const [it] = extract(mail({
    from: '明眸牙科 <service@dental.example.com>',
    subject: '预约成功确认',
    text: '您的洁牙预约已确认。\n预约时间：2026年12月2日 15:30\n地点：中山路88号\n确认码：CN8821',
  }));
  assert.equal(it.kind, 'appointment');
  assert.equal(it.when, '2026-12-02T15:30:00');
  assert.equal(it.where, '中山路88号');
  assert.equal(it.ref, 'CN8821');
  assert.equal(it.calendar, true);
});

test('zh: bill_due 账单到期', () => {
  const [it] = extract(mail({
    from: '招商示例银行 <card@bank.example.com>',
    subject: '11月账单已出，请注意到期还款',
    text: '本期应还金额：1,280.00元\n到期日：2026年12月15日\n账单号：ST202611',
  }));
  assert.equal(it.kind, 'bill_due');
  assert.deepEqual(it.amount, { value: 1280, currency: 'CNY' });
  assert.equal(it.when, '2026-12-15');
  assert.equal(it.calendar, true);
});

test('zh: security_code 验证码 never leaked', () => {
  const CODE = '904218';
  const [it] = extract(mail({
    from: '示例云 <no-reply@cloudcn.example.com>',
    subject: '登录验证码',
    text: `您的登录验证码为 ${CODE}，5分钟内有效，切勿泄露给他人。`,
  }));
  assert.equal(it.kind, 'security_code');
  assert.ok(!JSON.stringify(it).includes(CODE));
  assert.ok(!digestLine(it).includes(CODE));
});

// --- 6. Negative fixtures (at least 10 that MUST return []) ---

test('neg 1: newsletter with prices', () => {
  assert.deepEqual(extract(mail({
    from: 'Daily Tech <news@dailytech.example.com>',
    subject: 'Weekly Digest: Top Gadgets Under $99',
    text: 'Top stories this week: new headphones for $79.99 and smart plugs at $19.50. Unsubscribe from this newsletter.',
  })), []);
});

test('neg 2: marketing "order now" promo', () => {
  assert.deepEqual(extract(mail({
    from: 'ShoeClub <promo@shoeclub.example.com>',
    subject: 'Order now and get 40% off winter boots!',
    text: 'Limited-time offer: shop now and save $50 on every pair. Use promo code WINTER40.',
  })), []);
});

test('neg 3: marketing free shipping on orders', () => {
  assert.deepEqual(extract(mail({
    from: 'HomeGoods <deals@homegoods.example.com>',
    subject: 'Flash Sale: Free shipping on orders over $35',
    text: 'Shop the sale today. Free shipping on orders over $35 all weekend.',
  })), []);
});

test('neg 4: social like notification', () => {
  assert.deepEqual(extract(mail({
    from: 'SocialNet <notify@socialnet.example.com>',
    subject: 'Sam Patel liked your photo',
    text: 'Sam Patel liked your post from yesterday. View their profile.',
  })), []);
});

test('neg 5: social follower notification', () => {
  assert.deepEqual(extract(mail({
    from: 'SocialNet <notify@socialnet.example.com>',
    subject: 'Alex Kim started following you',
    text: 'Alex Kim started following you on SocialNet.',
  })), []);
});

test('neg 6: forwarded thread casually mentioning cancelled', () => {
  assert.deepEqual(extract(mail({
    from: 'Chris <chris@example.com>',
    subject: 'Fwd: Saturday hike plans',
    text: 'Hey, I heard the trail meetup might be cancelled because of rain.\n---------- Forwarded message ---------\nYour booking was confirmed earlier.',
  })), []);
});

test('neg 7: reply thread casually mentioning refund and order', () => {
  assert.deepEqual(extract(mail({
    from: 'Taylor <taylor@example.com>',
    subject: 'Re: Coffee supplies',
    text: 'Did they ever send a refund for that order? Let me know tomorrow.',
  })), []);
});

test('neg 8: email ABOUT refunds policy', () => {
  assert.deepEqual(extract(mail({
    from: 'ShopEase <legal@shopease.example.com>',
    subject: 'Updates to our refund policy',
    text: 'We are updating our updated refund policy starting next month. Learn how refunds work and who is eligible for a refund.',
  })), []);
});

test('neg 9: email ABOUT cancellation policy', () => {
  assert.deepEqual(extract(mail({
    from: 'StayHub <info@stayhub.example.com>',
    subject: 'Understanding our cancellation policy',
    text: 'Most listings offer free cancellation up to 48 hours before arrival. Read our cancellation policy for details.',
  })), []);
});

test('neg 10: newsletter mentioning delivered to your inbox', () => {
  assert.deepEqual(extract(mail({
    from: 'Morning Brief <brief@news.example.com>',
    subject: 'Your morning briefing',
    text: 'Fresh headlines delivered to your inbox every weekday at 7 AM.',
  })), []);
});

test('neg 11: casual friend message about lunch', () => {
  assert.deepEqual(extract(mail({
    from: 'Morgan <morgan@example.com>',
    subject: 'Lunch next week?',
    text: 'Want to grab tacos on Tuesday around noon? Let me know if that works.',
  })), []);
});

test('neg 12: empty mail', () => {
  assert.deepEqual(extract(mail({})), []);
  assert.deepEqual(extract(null), []);
});

// --- 7. digestLine tests ---

test('digestLine: concise format and <= 120 chars', () => {
  const line = digestLine({
    kind: 'appointment',
    title: 'Dentist',
    who: 'Bright Smile Dental',
    amount: null,
    when: '2026-12-02T15:30:00',
    end: null,
    where: null,
    ref: 'A1B2',
    calendar: true,
    confidence: 0.9,
    source: 'pattern',
  });
  assert.equal(line, 'Dec 2 15:30 appointment: Dentist (Bright Smile Dental) ref A1B2');
  assert.ok(line.length <= 120);

  const longLine = digestLine({
    kind: 'event',
    title: 'Annual International Conference on Sustainable Urban Architecture and Resilient Design Systems 2026',
    who: 'Global Design Association Worldwide',
    amount: { value: 499, currency: 'USD' },
    when: '2026-12-12T09:00:00',
    end: null,
    where: 'Convention Center Hall B, 999 Waterfront Boulevard, Portland, Oregon',
    ref: 'CONF-2026-998877',
    calendar: true,
    confidence: 0.95,
    source: 'jsonld',
  });
  assert.ok(longLine.length <= 120, `digestLine exceeded 120 chars: ${longLine.length}`);
  assert.ok(!longLine.includes('\n'));
});

// --- Runner ---

let passed = 0;
for (const t of tests) {
  try {
    t.fn();
    passed++;
  } catch (err) {
    console.error(`FAIL [${t.name}]:`, err.message);
    process.exitCode = 1;
  }
}

if (process.exitCode) {
  console.error(`${passed}/${tests.length} passed`);
} else {
  console.log(`${passed}/${tests.length} tests passed`);
  { const t = extract({ from: 'StreamCo <billing@stream.example>', subject: 'Your free trial ends soon', date: '2026-11-20T10:00:00Z', text: 'Your free trial ends on December 2, 2026. After that you will be charged $15.99 per month.', html: '', attachments: [] });
  assert.equal(t[0]?.kind, 'trial_ending'); assert.equal(t[0]?.when, '2026-12-02'); }
{ const t = extract({ from: 'Shop <orders@shop.example>', subject: 'Your order was delivered', date: '2026-11-20T10:00:00Z', text: 'Thanks for shopping. Return window closes on December 20, 2026.', html: '', attachments: [] });
  assert.ok(t.some(x => x.kind === 'return_deadline' || x.kind === 'delivered')); }
console.log('extract ok');
}
