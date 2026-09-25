# Master Plan: Personal Cognitive Companion & Usability Evolution (`BUILD_PLAN.md`)

This master plan supersedes all previous plans. It focuses on transforming Shiina into a high-value personal cognitive companion with deep usability, proactive intelligence, and seamless voice/knowledge workflows.

---

## Phase 1: Smart Contextual Briefings & Proactive Awareness
- **Goal:** Intelligent, context-aware check-ins based on local device state (calendar, upcoming notifications, battery/charging patterns, time-of-day).
- **Implementation:**
  - Expand `ProactiveLoop.kt` to pull calendar events, battery status, and unread notifications via Android system services.
  - Formulate structured briefing context for proactive model prompts.
  - Implement smart timing gates so Shiina checks in only at relevant moments (morning pickup, post-work transition, low battery warnings).
- **Verification:** Unit test briefing payload generation; verify proactive trigger execution on Huawei device.

## Phase 2: Voice-First Conversational Fluidity & Barge-In
- **Goal:** Natural, uninterrupted voice interaction making talking to Shiina feel like a phone call.
- **Implementation:**
  - Optimize `VoiceCloneEngine.kt` and `ShiinaVoiceSpeaker.kt` for low-latency streaming playback.
  - Implement conversational barge-in capability (pausing speech output immediately when user speaks or interrupts).
  - Refine emotional acoustic modulation (pitch and rate) for natural conversational cadence.
- **Verification:** Test audio streaming responsiveness and barge-in interruption flow.

## Phase 3: Automated Personal Knowledge Capture & Structured Journaling
- **Goal:** Act as an external brain that remembers ideas, reflections, and tasks shared during chat.
- **Implementation:**
  - Build automated extraction pipeline in `DynamicLearningEngine.kt` or dedicated `KnowledgeCaptureWorker`.
  - Automatically categorize and store user notes into structured local storage / markdown notes organized by project/topic.
  - Expose searchable memory/journal retrieval in settings and conversational recall.
- **Verification:** Test idea extraction accuracy and local storage persistence.

## Phase 4: Intelligent Notification Triage & Quick-Action Voice Summary
- **Goal:** Filter notification noise and provide instant summaries with one-tap voice reply.
- **Implementation:**
  - Enhance `NotificationListenerService` integration to capture incoming pings.
  - Group and summarize low-priority notifications while alerting on crucial messages.
  - Add quick action triggers for voice replies directly from the character overlay.
- **Verification:** Test notification listener event capture and summary generation.
