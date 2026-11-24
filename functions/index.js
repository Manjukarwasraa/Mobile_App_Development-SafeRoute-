const crypto = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onTaskDispatched } = require("firebase-functions/v2/tasks");
const { defineSecret, defineString } = require("firebase-functions/params");
const { getFunctions } = require("firebase-admin/functions");

initializeApp();
const db = getFirestore();
const messaging = getMessaging();
const msg91AuthKey = defineSecret("MSG91_AUTH_KEY");
const msg91TemplateId = defineString("MSG91_TEMPLATE_ID");

const CHECK_INTERVALS = [5, 10, 15];
const RESPONSE_WINDOW_SECONDS = 60;
const users = (uid) => db.collection("users").doc(uid);
const journeyRef = (uid, journeyId) => users(uid).collection("journeys").doc(journeyId);
const checkInRef = (uid, checkInId) => users(uid).collection("safetyCheckIns").doc(checkInId);
function taskQueue(functionName) {
  return getFunctions().taskQueue(functionName);
}

async function enqueue(functionName, data, when, id) {
  try {
    await taskQueue(functionName).enqueue(data, { scheduleTime: when, id });
  } catch (error) {
    // A deterministic task ID makes duplicate trigger deliveries harmless.
    if (error.code !== "functions/task-already-exists" && error.code !== 6 && error.code !== "ALREADY_EXISTS") {
      throw error;
    }
  }
}

function intervalMinutes(journey) {
  const minutes = Number(journey.safetyCheckIntervalMinutes);
  return CHECK_INTERVALS.includes(minutes) ? minutes : 15;
}

async function scheduleCheck(uid, journeyId, generation, sequence, minutes) {
  const scheduleTime = new Date(Date.now() + minutes * 60 * 1000);
  await enqueue("deliverSafetyCheck", { uid, journeyId, generation, sequence }, scheduleTime,
    checkTaskId(uid, journeyId, generation, sequence));
}

function checkTaskId(uid, journeyId, generation, sequence) {
  return `check_${crypto.createHash("sha256").update(`${uid}_${journeyId}_${generation}_${sequence}`).digest("hex").slice(0, 40)}`;
}

function timeoutTaskId(uid, checkInId) {
  return `timeout_${crypto.createHash("sha256").update(`${uid}_${checkInId}`).digest("hex").slice(0, 40)}`;
}

async function deleteTask(functionName, taskId) {
  try {
    await taskQueue(functionName).delete(taskId);
  } catch (error) {
    const code = error.code;
    if (code !== 5 && code !== "NOT_FOUND" && code !== "functions/task-not-found") {
      console.error("Could not cancel a queued safety task; Firestore lifecycle checks remain active", { functionName, code });
    }
  }
}

exports.journeyMonitoringLifecycle = onDocumentWritten(
  "users/{uid}/journeys/{journeyId}",
  async (event) => {
    const { uid, journeyId } = event.params;
    const before = event.data?.before?.data();
    const after = event.data?.after?.data();
    const ref = journeyRef(uid, journeyId);
    if (!after) return;

    if (after.status !== "active") {
      if (before?.status === "active") {
        let cancelled = null;
        await db.runTransaction(async (transaction) => {
          const snapshot = await transaction.get(ref);
          const current = snapshot.data();
          if (current?.status !== "active" && current?.safetyMonitoringActive === true) {
            const activeCheckInId = current.activeSafetyCheckInId || null;
            const activeCheckRef = activeCheckInId ? checkInRef(uid, activeCheckInId) : null;
            const activeCheckSnapshot = activeCheckRef ? await transaction.get(activeCheckRef) : null;
            if (activeCheckRef && activeCheckSnapshot?.get("status") === "pending") {
              transaction.update(activeCheckRef, {
                status: "cancelled",
                response: "cancelled",
                cancelledAt: FieldValue.serverTimestamp()
              });
            }
            cancelled = {
              generation: Number(current.safetyMonitoringGeneration || 0),
              sequence: Number(current.safetyMonitoringSequence || 0),
              activeCheckInId
            };
            transaction.update(ref, {
              safetyMonitoringActive: false,
              safetyMonitoringPaused: false,
              safetyMonitoringPauseReason: null,
              safetyMonitoringGeneration: Number(current.safetyMonitoringGeneration || 0) + 1,
              activeSafetyCheckInId: null
            });
          }
        });
        if (cancelled) {
          await deleteTask("deliverSafetyCheck", checkTaskId(uid, journeyId, cancelled.generation, cancelled.sequence));
          if (cancelled.activeCheckInId) {
            await deleteTask("expireSafetyCheck", timeoutTaskId(uid, cancelled.activeCheckInId));
          }
        }
      }
      return;
    }

    let state;
    let startedNow = false;
    await db.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(ref);
      const current = snapshot.data();
      if (!snapshot.exists || current?.status !== "active") return;
      let generation = Number(current.safetyMonitoringGeneration || 0);
      let sequence = Number(current.safetyMonitoringSequence || 0);
      if (current.safetyMonitoringActive !== true && generation === 0 &&
        current.safetyMonitoringPauseReason !== "emergency_triggered") {
        startedNow = true;
        generation += 1;
        sequence = 1;
        transaction.update(ref, {
          safetyMonitoringActive: true,
          safetyMonitoringPaused: false,
          safetyMonitoringPauseReason: null,
          safetyMonitoringGeneration: generation,
          safetyMonitoringSequence: sequence,
          activeSafetyCheckInId: null,
          safetyMonitoringStartedAt: FieldValue.serverTimestamp()
        });
      }
      state = {
        generation,
        sequence: sequence || 1,
        active: current.safetyMonitoringActive === true || startedNow,
        paused: current.safetyMonitoringPaused === true,
        interval: intervalMinutes(current)
      };
    });

    if (state?.active && !state.paused && state.sequence > 0) {
      await scheduleCheck(uid, journeyId, state.generation, state.sequence, state.interval);
    }
  }
);

exports.deliverSafetyCheck = onTaskDispatched(
  { retryConfig: { maxAttempts: 5, minBackoffSeconds: 10, maxBackoffSeconds: 60 }, rateLimits: { maxConcurrentDispatches: 20 } },
  async (request) => {
    const { uid, journeyId, generation, sequence } = request.data;
    const ref = journeyRef(uid, journeyId);
    const id = `${journeyId}_${generation}_${sequence}`;
    const checkRef = checkInRef(uid, id);
    const now = Date.now();
    let shouldSend = false;
    let ensureTimeout = false;
    let deadlineForTimeout = null;

    await db.runTransaction(async (transaction) => {
      const journeySnapshot = await transaction.get(ref);
      const journey = journeySnapshot.data();
      if (!journeySnapshot.exists || journey?.status !== "active" ||
        journey.safetyMonitoringActive !== true || journey.safetyMonitoringPaused === true ||
        Number(journey.safetyMonitoringGeneration) !== Number(generation) ||
        Number(journey.safetyMonitoringSequence) !== Number(sequence)) return;

      const existing = await transaction.get(checkRef);
      if (existing.exists) {
        if (existing.get("status") === "pending" && existing.get("notificationStatus") !== "sent") {
          shouldSend = true;
        } else if (existing.get("status") === "pending") {
          ensureTimeout = true;
          deadlineForTimeout = existing.get("deadlineAt")?.toDate?.() || new Date(now + RESPONSE_WINDOW_SECONDS * 1000);
        }
        return;
      }
      const activeId = journey.activeSafetyCheckInId;
      if (activeId && activeId !== id) return;

      transaction.create(checkRef, {
        journeyId,
        checkInId: id,
        monitoringGeneration: generation,
        sequence,
        response: "pending",
        status: "pending",
        createdAt: FieldValue.serverTimestamp(),
        deadlineAt: null,
        respondedAt: null,
        emergencyTriggered: false,
        alertReason: null,
        notificationStatus: "sending"
      });
      transaction.update(ref, { activeSafetyCheckInId: id });
      shouldSend = true;
    });

    if (!shouldSend) {
      if (ensureTimeout && deadlineForTimeout) {
        await enqueue("expireSafetyCheck", { uid, journeyId, checkInId: id, generation }, deadlineForTimeout,
          timeoutTaskId(uid, id));
      }
      return;
    }
    const [latestJourney, latestCheckIn] = await Promise.all([ref.get(), checkRef.get()]);
    if (latestJourney.get("status") !== "active" || latestJourney.get("safetyMonitoringActive") !== true ||
      latestJourney.get("safetyMonitoringPaused") === true ||
      Number(latestJourney.get("safetyMonitoringGeneration")) !== Number(generation) ||
      latestJourney.get("activeSafetyCheckInId") !== id || latestCheckIn.get("status") !== "pending") {
      if (latestCheckIn.exists && latestCheckIn.get("status") === "pending") {
        await checkRef.update({ status: "cancelled", response: "cancelled", cancelledAt: FieldValue.serverTimestamp() });
      }
      return;
    }
    const deviceSnapshot = await users(uid).collection("safetyDevices").get();
    const devices = deviceSnapshot.docs.filter((doc) =>
      doc.get("notificationsEnabled") === true && typeof doc.get("token") === "string");

    if (devices.length === 0) {
      await pauseForNotificationFailure(uid, journeyId, id, generation, sequence, "No enabled notification device is registered.");
      return;
    }

    let result;
    try {
      result = await messaging.sendEachForMulticast({
        tokens: devices.map((doc) => doc.get("token")),
        data: {
          type: "safety_check_in",
          checkInId: id,
          journeyId,
          title: "SafeRoute safety check",
          body: "SafeRoute is checking your journey safety. Please respond within 60 seconds."
        },
        android: { priority: "high", ttl: 60 * 1000 }
      });
    } catch (error) {
      await pauseForNotificationFailure(uid, journeyId, id, generation, sequence, error.message);
      return;
    }

    const invalidTokenDeletes = [];
    result.responses.forEach((response, index) => {
      const code = response.error?.code || "";
      if (code.includes("registration-token-not-registered") || code.includes("invalid-registration-token")) {
        invalidTokenDeletes.push(devices[index].ref.delete());
      }
    });
    await Promise.all(invalidTokenDeletes);
    if (result.successCount === 0) {
      await pauseForNotificationFailure(uid, journeyId, id, generation, sequence, "FCM did not accept the notification for any device.");
      return;
    }

    const deadline = new Date(Date.now() + RESPONSE_WINDOW_SECONDS * 1000);
    deadlineForTimeout = deadline;
    await checkRef.update({
      notificationStatus: "sent",
      notificationAcceptedCount: result.successCount,
      notificationFailedCount: result.failureCount,
      notificationSentAt: FieldValue.serverTimestamp(),
      deadlineAt: Timestamp.fromDate(deadline)
    });
    await enqueue("expireSafetyCheck", { uid, journeyId, checkInId: id, generation }, deadlineForTimeout,
      timeoutTaskId(uid, id));
  }
);

async function pauseForNotificationFailure(uid, journeyId, checkInId, generation, sequence, message) {
  const ref = journeyRef(uid, journeyId);
  await db.runTransaction(async (transaction) => {
    const [journeySnapshot, checkSnapshot] = await Promise.all([transaction.get(ref), transaction.get(checkInRef(uid, checkInId))]);
    const journey = journeySnapshot.data();
    if (checkSnapshot.exists && checkSnapshot.get("status") === "pending") {
      transaction.update(checkInRef(uid, checkInId), {
        response: "not_delivered",
        status: "delivery_failed",
        notificationStatus: "failed",
        notificationError: String(message).slice(0, 300),
        failedAt: FieldValue.serverTimestamp()
      });
    }
    if (journeySnapshot.exists && journey?.status === "active" &&
      Number(journey.safetyMonitoringGeneration) === Number(generation) &&
      Number(journey.safetyMonitoringSequence) === Number(sequence)) {
      transaction.update(ref, {
        safetyMonitoringPaused: true,
        safetyMonitoringPauseReason: "notification_unavailable",
        safetyMonitoringSequence: Number(sequence) + 1,
        activeSafetyCheckInId: null
      });
    }
  });
  console.error("Safety check-in notification could not be delivered", { uid, journeyId, checkInId, message });
}

exports.expireSafetyCheck = onTaskDispatched(
  { retryConfig: { maxAttempts: 5, minBackoffSeconds: 10, maxBackoffSeconds: 60 } },
  async (request) => {
    const { uid, journeyId, checkInId, generation } = request.data;
    const checkRef = checkInRef(uid, checkInId);
    const ref = journeyRef(uid, journeyId);
    let shouldSendEmergency = false;
    await db.runTransaction(async (transaction) => {
      const [journeySnapshot, checkSnapshot] = await Promise.all([transaction.get(ref), transaction.get(checkRef)]);
      const journey = journeySnapshot.data();
      const check = checkSnapshot.data();
      if (!checkSnapshot.exists || check?.status !== "pending") return;
      if (!journeySnapshot.exists || journey?.status !== "active" ||
        journey.safetyMonitoringActive !== true || journey.safetyMonitoringPaused === true ||
        Number(journey.safetyMonitoringGeneration) !== Number(generation) ||
        journey.activeSafetyCheckInId !== checkInId) {
        transaction.update(checkRef, { status: "cancelled", cancelledAt: FieldValue.serverTimestamp() });
        return;
      }
      const deadline = check.deadlineAt?.toMillis?.() || 0;
      if (deadline > Date.now()) return;
      transaction.update(checkRef, {
        response: "no_response",
        status: "timed_out",
        respondedAt: FieldValue.serverTimestamp(),
        emergencyTriggered: true,
        alertReason: "missed_check_in",
        timedOutAt: FieldValue.serverTimestamp()
      });
      transaction.update(ref, {
        safetyMonitoringActive: false,
        safetyMonitoringPaused: true,
        safetyMonitoringPauseReason: "emergency_triggered",
        activeSafetyCheckInId: null
      });
      shouldSendEmergency = true;
    });

    if (shouldSendEmergency) {
      await enqueue("sendEmergencySms", { uid, journeyId, checkInId }, new Date(),
        `sms_${crypto.createHash("sha256").update(`${uid}_${checkInId}`).digest("hex").slice(0, 40)}`);
    }
  }
);

exports.registerSafetyDevice = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to register this device.");
  const token = String(request.data?.token || "");
  if (token.length < 20 || token.length > 4096) throw new HttpsError("invalid-argument", "Invalid FCM registration token.");
  const enabled = request.data?.notificationsEnabled === true;
  const tokenId = crypto.createHash("sha256").update(token).digest("hex");
  await users(request.auth.uid).collection("safetyDevices").doc(tokenId).set({
    token,
    notificationsEnabled: enabled,
    updatedAt: FieldValue.serverTimestamp()
  }, { merge: true });

  if (enabled) await resumeAfterNotificationPermission(request.auth.uid);
  return { registered: true };
});

exports.saveJourneyLocation = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to save a journey location.");
  const { journeyId, locationLink } = request.data || {};
  if (typeof journeyId !== "string" || typeof locationLink !== "string" || locationLink.length > 300) {
    throw new HttpsError("invalid-argument", "The location data is invalid.");
  }
  let locationUrl;
  try {
    locationUrl = new URL(locationLink);
  } catch (_error) {
    throw new HttpsError("invalid-argument", "The location link is invalid.");
  }
  const coordinates = locationUrl.searchParams.get("q")?.split(",").map(Number);
  if (locationUrl.protocol !== "https:" || locationUrl.hostname !== "maps.google.com" ||
    coordinates?.length !== 2 || coordinates.some((coordinate) => !Number.isFinite(coordinate)) ||
    Math.abs(coordinates[0]) > 90 || Math.abs(coordinates[1]) > 180) {
    throw new HttpsError("invalid-argument", "The location link is not a valid Maps coordinate link.");
  }
  const ref = journeyRef(request.auth.uid, journeyId);
  const snapshot = await ref.get();
  if (!snapshot.exists || snapshot.get("status") !== "active") {
    throw new HttpsError("failed-precondition", "Only the active journey can save a location.");
  }
  await ref.update({
    lastLocationLink: locationUrl.toString(),
    lastLocationRecordedAt: FieldValue.serverTimestamp()
  });
  return { saved: true };
});

async function resumeAfterNotificationPermission(uid) {
  const journeys = await users(uid).collection("journeys").where("status", "==", "active").get();
  for (const snapshot of journeys.docs) {
    const ref = snapshot.ref;
    let next;
    await db.runTransaction(async (transaction) => {
      const currentSnapshot = await transaction.get(ref);
      const journey = currentSnapshot.data();
      if (journey?.status !== "active") return;
      let generation = Number(journey.safetyMonitoringGeneration || 0);
      let sequence = Number(journey.safetyMonitoringSequence || 0);
      if (journey.safetyMonitoringPauseReason === "notification_unavailable") {
        sequence = sequence || 1;
        transaction.update(ref, {
          safetyMonitoringActive: true,
          safetyMonitoringPaused: false,
          safetyMonitoringPauseReason: null,
          activeSafetyCheckInId: null
        });
      } else if (generation === 0 && journey.safetyMonitoringActive !== true) {
        // Also enroll a journey that was already active when these functions were deployed.
        generation = 1;
        sequence = 1;
        transaction.update(ref, {
          safetyMonitoringActive: true,
          safetyMonitoringPaused: false,
          safetyMonitoringSequence: sequence,
          safetyMonitoringGeneration: generation,
          activeSafetyCheckInId: null,
          safetyMonitoringStartedAt: FieldValue.serverTimestamp()
        });
      } else if (journey.safetyMonitoringActive === true && journey.safetyMonitoringPaused !== true &&
        !journey.activeSafetyCheckInId) {
        sequence = sequence || 1;
      } else {
        return;
      }
      next = { generation, sequence, interval: intervalMinutes(journey) };
    });
    if (next) await scheduleCheck(uid, snapshot.id, next.generation, next.sequence, next.interval);
  }
}

exports.submitSafetyResponse = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to respond to a safety check.");
  const { journeyId, checkInId, response } = request.data || {};
  if (typeof journeyId !== "string" || typeof checkInId !== "string" || !["safe", "help"].includes(response)) {
    throw new HttpsError("invalid-argument", "The safety response is invalid.");
  }
  const uid = request.auth.uid;
  const ref = journeyRef(uid, journeyId);
  const checkRef = checkInRef(uid, checkInId);
  let next = null;
  let late = false;

  await db.runTransaction(async (transaction) => {
    const [journeySnapshot, checkSnapshot] = await Promise.all([transaction.get(ref), transaction.get(checkRef)]);
    const journey = journeySnapshot.data();
    const check = checkSnapshot.data();
    if (!journeySnapshot.exists || journey?.status !== "active" ||
      journey.safetyMonitoringActive !== true || journey.safetyMonitoringPaused === true ||
      journey.activeSafetyCheckInId !== checkInId ||
      Number(journey.safetyMonitoringGeneration) !== Number(check?.monitoringGeneration) ||
      !checkSnapshot.exists || check?.journeyId !== journeyId || check?.status !== "pending") {
      throw new HttpsError("failed-precondition", "This safety check is no longer active.");
    }
    if (check.notificationStatus !== "sent" || !check.deadlineAt?.toMillis) {
      throw new HttpsError("unavailable", "The safety check is still being delivered. Please try again in a moment.");
    }
    if ((check.deadlineAt?.toMillis?.() || 0) <= Date.now()) {
      transaction.update(checkRef, {
        response: "no_response",
        status: "timed_out",
        respondedAt: FieldValue.serverTimestamp(),
        emergencyTriggered: true,
        alertReason: "missed_check_in",
        timedOutAt: FieldValue.serverTimestamp()
      });
      transaction.update(ref, {
        safetyMonitoringActive: false,
        safetyMonitoringPaused: true,
        safetyMonitoringPauseReason: "emergency_triggered",
        activeSafetyCheckInId: null
      });
      late = true;
      return;
    }

    transaction.update(checkRef, {
      response,
      status: "responded",
      respondedAt: FieldValue.serverTimestamp(),
      emergencyTriggered: false,
      alertReason: null
    });
    if (response === "help") {
      transaction.update(ref, {
        safetyMonitoringPaused: true,
        safetyMonitoringPauseReason: "user_requested_help",
        activeSafetyCheckInId: null
      });
    } else {
      const sequence = Number(journey.safetyMonitoringSequence || 0) + 1;
      transaction.update(ref, { safetyMonitoringSequence: sequence, activeSafetyCheckInId: null });
      if (journey.safetyMonitoringActive === true && journey.safetyMonitoringPaused !== true) {
        next = { sequence, generation: Number(journey.safetyMonitoringGeneration || 0), interval: intervalMinutes(journey) };
      }
    }
  });

  if (late) {
    await enqueue("sendEmergencySms", { uid, journeyId, checkInId }, new Date(),
      `sms_${crypto.createHash("sha256").update(`${uid}_${checkInId}`).digest("hex").slice(0, 40)}`);
    return { accepted: false, late: true };
  }
  if (next) await scheduleCheck(uid, journeyId, next.generation, next.sequence, next.interval);
  return { accepted: true, response };
});

exports.resumeSafetyMonitoring = onCall(async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to resume safety monitoring.");
  const { journeyId, safeToContinue } = request.data || {};
  if (typeof journeyId !== "string" || safeToContinue !== true) {
    throw new HttpsError("invalid-argument", "Confirm that you are safe to continue.");
  }
  const uid = request.auth.uid;
  const ref = journeyRef(uid, journeyId);
  let next = null;
  await db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(ref);
    const journey = snapshot.data();
    if (journey?.status !== "active" || journey.safetyMonitoringPauseReason !== "user_requested_help") {
      throw new HttpsError("failed-precondition", "Monitoring is not waiting for this confirmation.");
    }
    const sequence = Number(journey.safetyMonitoringSequence || 0) + 1;
    transaction.update(ref, {
      safetyMonitoringActive: true,
      safetyMonitoringPaused: false,
      safetyMonitoringPauseReason: null,
      safetyMonitoringSequence: sequence,
      activeSafetyCheckInId: null
    });
    next = { sequence, generation: Number(journey.safetyMonitoringGeneration || 0), interval: intervalMinutes(journey) };
  });
  if (next) await scheduleCheck(uid, journeyId, next.generation, next.sequence, next.interval);
  return { resumed: true };
});

exports.sendEmergencySms = onTaskDispatched(
  { retryConfig: { maxAttempts: 3, minBackoffSeconds: 10, maxBackoffSeconds: 60 }, secrets: [msg91AuthKey] },
  async (request) => {
    const { uid, journeyId, checkInId } = request.data;
    const checkRef = checkInRef(uid, checkInId);
    const checkSnapshot = await checkRef.get();
    if (!checkSnapshot.exists || checkSnapshot.get("response") !== "no_response" ||
      checkSnapshot.get("alertReason") !== "missed_check_in" || checkSnapshot.get("emergencyTriggered") !== true) return;

    const [journeySnapshot, profileSnapshot, contactsSnapshot] = await Promise.all([
      journeyRef(uid, journeyId).get(), users(uid).get(), users(uid).collection("contacts").get()
    ]);
    const journey = journeySnapshot.data() || {};
    const name = profileSnapshot.get("name") || "SafeRoute user";
    const link = journey.lastLocationLink;
    const recordedAt = journey.lastLocationRecordedAt?.toDate?.();
    const recordedText = recordedAt ? recordedAt.toISOString() : "not available";
    const destination = journey.to || "destination not available";
    const contacts = contactsSnapshot.docs.map((doc) => ({
      name: String(doc.get("name") || "Emergency contact"),
      phone: String(doc.get("phone") || "")
    }));

    if (contacts.length === 0) {
      await checkRef.update({ smsStatus: "no_contacts", smsFinishedAt: FieldValue.serverTimestamp() });
      return;
    }

    for (const contact of contacts) {
      const mobile = normalizeIndianMobile(contact.phone);
      const recordId = crypto.createHash("sha256").update(mobile || contact.phone.replace(/\D/g, "")).digest("hex").slice(0, 40);
      const deliveryRef = checkRef.collection("smsDeliveries").doc(recordId);
      const shouldSend = await db.runTransaction(async (transaction) => {
        const current = await transaction.get(deliveryRef);
        if (current.exists) return false;
        transaction.create(deliveryRef, {
          contactName: contact.name,
          maskedPhone: maskPhone(mobile || contact.phone),
          status: mobile ? "sending" : "invalid_phone",
          provider: "MSG91",
          attemptedAt: FieldValue.serverTimestamp()
        });
        return mobile !== null;
      });
      if (!shouldSend) {
        const previous = await deliveryRef.get();
        const requestId = previous.get("providerRequestId");
        if (previous.get("status") === "provider_accepted" && requestId) {
          await scheduleSmsReconciliation({ uid, checkInId, recipientId: recordId, mobile, requestId });
        }
        continue;
      }

      const templateId = msg91TemplateId.value();
      if (!templateId || !msg91AuthKey.value()) {
        await deliveryRef.update({ status: "configuration_missing", detail: "MSG91 template or secret is not configured." });
        continue;
      }

      try {
        const response = await fetch("https://control.msg91.com/api/v5/flow", {
          method: "POST",
          headers: { accept: "application/json", authkey: msg91AuthKey.value(), "content-type": "application/json" },
          body: JSON.stringify({
            template_id: templateId,
            realTimeResponse: "1",
            recipients: [{
              mobiles: mobile,
              VAR1: name.slice(0, 60),
              VAR2: String(destination).slice(0, 80),
              VAR3: link ? String(link).slice(0, 160) : "Location unavailable",
              VAR4: recordedText,
              VAR5: "No response to the scheduled safety check-in"
            }]
          })
        });
        const result = await response.json().catch(() => ({}));
        if (!response.ok) {
          await deliveryRef.update({ status: "provider_rejected", httpStatus: response.status, providerMessage: JSON.stringify(result).slice(0, 500) });
          continue;
        }
        const requestId = result.request_id || result.requestId || result.data?.request_id || null;
        await deliveryRef.update({
          status: "provider_accepted",
          providerRequestId: requestId,
          providerResponse: JSON.stringify(result).slice(0, 500),
          acceptedAt: FieldValue.serverTimestamp()
        });
        if (requestId) {
          await scheduleSmsReconciliation({ uid, checkInId, recipientId: recordId, mobile, requestId });
        }
      } catch (error) {
        // Network errors can mean MSG91 accepted the SMS but the response was lost.
        // Keep the record ambiguous and do not resend automatically.
        await deliveryRef.update({ status: "provider_result_unknown", detail: String(error.message).slice(0, 300) });
        console.error("MSG91 request result is unknown; not retrying to avoid a duplicate SMS", { uid, checkInId, recordId });
      }
    }

    await checkRef.update({ smsStatus: "attempted", smsFinishedAt: FieldValue.serverTimestamp() });
  }
);

async function scheduleSmsReconciliation({ uid, checkInId, recipientId, mobile, requestId }) {
  for (const attempt of [1, 2]) {
    await enqueue("reconcileSmsDelivery", { uid, checkInId, recipientId, mobile, requestId, attempt },
      new Date(Date.now() + (attempt === 1 ? 3 : 15) * 60 * 1000), `sms_receipt_${recipientId}_${attempt}`);
  }
}

exports.reconcileSmsDelivery = onTaskDispatched(
  { retryConfig: { maxAttempts: 3, minBackoffSeconds: 10, maxBackoffSeconds: 60 }, secrets: [msg91AuthKey] },
  async (request) => {
    const { uid, checkInId, recipientId, mobile, requestId, attempt } = request.data;
    const deliveryRef = checkInRef(uid, checkInId).collection("smsDeliveries").doc(recipientId);
    const current = await deliveryRef.get();
    if (!current.exists || current.get("status") !== "provider_accepted") return;
    const today = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit"
    }).format(new Date());
    const url = new URL("https://control.msg91.com/api/v5/report/logs/p/sms");
    url.search = new URLSearchParams({ startDate: today, endDate: today, requestId, fields: "status,deliveryDate,deliveryTime,telNum,requestId,failureReason,statusCode" }).toString();
    const response = await fetch(url, { method: "POST", headers: { accept: "application/json", authkey: msg91AuthKey.value() } });
    if (!response.ok) throw new Error(`MSG91 report lookup returned HTTP ${response.status}`);
    const report = await response.json();
    const rows = Array.isArray(report.data) ? report.data : [];
    const row = rows.find((entry) => String(entry.telNum || "").replace(/\D/g, "") === mobile);
    if (row) {
      const providerStatus = String(row.status || "").toLowerCase();
      const status = providerStatus === "delivered" ? "delivered" :
        (providerStatus.includes("fail") || providerStatus.includes("reject") ? "delivery_failed" : "provider_accepted");
      await deliveryRef.update({
        status,
        providerDeliveryStatus: row.status || "unknown",
        deliveryDate: row.deliveryDate || null,
        deliveryTime: row.deliveryTime || null,
        failureReason: row.failureReason || null,
        reconciledAt: FieldValue.serverTimestamp()
      });
    }
    if (!row && attempt === 1) {
      await enqueue("reconcileSmsDelivery", { ...request.data, attempt: 2 }, new Date(Date.now() + 12 * 60 * 1000), `sms_receipt_${recipientId}_2`);
    }
  }
);

function normalizeIndianMobile(value) {
  const raw = String(value || "").trim();
  let digits = raw.replace(/[^0-9]/g, "");
  if (digits.length === 10 && /^[6-9]/.test(digits)) digits = `91${digits}`;
  else if (digits.length === 12 && digits.startsWith("91")) { /* already India E.164 without + */ }
  else return null;
  return /^91[6-9][0-9]{9}$/.test(digits) ? digits : null;
}

function maskPhone(value) {
  const digits = String(value || "").replace(/\D/g, "");
  return digits.length < 4 ? "***" : `******${digits.slice(-4)}`;
}
