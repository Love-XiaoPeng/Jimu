# P4 task image implementation

This implements [ADR-0006](adr/0006-task-image-input.md) and the [accepted architecture contract](implementation-p4-read-image.md). The original architecture captures under `verification/XIAO-46/architecture` remain historical evidence. Product checks and their limits are recorded separately. Local protocol tests do not establish real model visual understanding.

## Admission and configuration

Images are disabled by default. Control and worker must use the same dedicated non-root identity, private native POSIX storage, and fixed model/plugin versions. Set `jimu.images.enabled=true` and `jimu.images.root=/operator/private-image-root`; provision the root with mode 0700. Start the worker with `-Xmx256m` or less. Existing artifact and workspace roots remain separate. Root and every ancestor must be reached without symbolic links; SecureDirectoryStream and Linux procfs are required. The namespace is flat: SHA256(tenant) prefixes a host-issued image UUID and content hash. No model-selected logical path becomes an OS path.

Use a new model profile with `version=3`, `adapter=openai-chat-images-v1`, and the existing explicit transport/auth/call/tool/usage settings. The required `image-input` object is:

```properties
image-input.version=1
image-input.enabled=true
image-input.media-types[0]=image/png
image-input.max-images=4
image-input.max-image-bytes=1048576
image-input.max-total-image-bytes=4194304
image-input.max-request-bytes=8388608
image-input.evidence=<reviewed capability evidence>
```

The test driver contains a complete loopback, auth=none, paid=false configuration with ordinary and SSE profiles. It does not install a production profile. The new DeepSeek image strategy retains non-thinking request fields and requires an explicit v2 image business policy. Paid image requests are rejected with `MODEL_INPUT_BOUND_UNAVAILABLE`: no multimodal billing bound is registered. Official Flash vision support does not upgrade old v2 profiles or authorize any cloud call.

Operator installation rows must pin `read_image@0.1.0` and, for image reports, `compose_report@0.2.0`, with packaged output-schema hashes. Migrations never install or upgrade tenant plugins. Enabled catalog discovery offers the new compose version; already admitted v1 tasks still load their exact old contract. Admission rechecks store availability, explicit model capability, installation epoch, owner access, and MySQL `max_allowed_packet >= 16MiB`. Unsupported model/store errors occur before creating a task or reserving its budget. Raw HTTP input is authenticated first and bounded to 6MiB.

## Task and model protocol

`POST /api/tasks` adds optional `images: [{filePath,dataBase64}]`. Only canonical strict Base64 is accepted; each input is 1..1MiB, the total at most 4MiB, with at most four distinct non-overlapping paths below `inputs/images/`. Paths are case-sensitive and reject absolute paths, URLs, backslashes, empty/dot/parent components and control characters. Request identity and idempotency use ordered path/hash/size descriptors, never raw Base64. Changing bytes under the same idempotency key conflicts. Images and task admission commit atomically under V13.

The directory is a read-only task namespace. `read_image` returns metadata only. Text reads reject image paths, and text mutation/compose/present cannot target the image prefix. The worker signature-checks PNG/JPEG, validates containers, dimensions and EXIF orientation, then fully decodes with JDK java.desktop PNG/JPEG readers. Third-party codecs are not selected and disk caching is not used. PNG ICC inflation and JPEG ICC accumulation are bounded to 64KiB; malformed ICC fails. Ancillary text metadata is ignored. PNG ICC is converted to sRGB, JPEG uses the JDK's ICC conversion; output strips source metadata. Alpha and 16-bit grayscale are covered by normalization tests. GIF/WebP/APNG and unsupported JPEG color/frame variants are rejected. Output is a real PNG within 2048 pixels per side, 1,048,576 pixels, and 1MiB; it never upscales.

The existing tracked worker operation performs decoding with a 30-second/task-deadline bound and authority rechecks. A cancelled Future is not quiescence. Interrupted, expired or revoked decoders request the existing supervisor's stop deadline; the operation remains live until the Runnable exits. Tests deliberately emulate an interruption-resistant decoder and distinguish that simulation from an observed JDK codec hang.

The only ToolCallingAdvisor loop completes all tool replies, then appends host user media parts in slot order. Raw pixels appear as actual `image_url` data parts, not text/tool JSON. Injection deduplicates `(inputId, normalized SHA256)`. The original goal stays unchanged; a separate host directory message lists authorized inputs. Checkpoint3 records exact final request bytes/digest plus scoped immutable references and is bounded to 12MiB. Before dispatch, the ledger reconstructs exact messages and fixed tool schemas/descriptions from confirmed history, reloads the stored bytes, and verifies their hashes and authorization. Old Request/WireMessage/ModelSnapshot2 omit the additive null fields and preserve prior wire bytes.

## Reports, storage and retention

Image report draft2 accepts typed web/image segments. Web segments reuse the existing exact-quote checker. Image segments cite a successful task-local invocation and render explicitly as model inference. The composition receipt binds the image and the confirmed model request/response that produced that compose call. That request must already contain the real image part; a foreign reference or a reference first read in the same response cannot establish evidence. Presentation replays the exact composition receipt and current file version, preserving existing edit and download authorization checks. The host adds the local protocol disclaimer when applicable.

Raw media and normalized objects use the existing MEDIA_INPUT/MEDIA_STORAGE reservations. Normalization reserves the full 1MiB before decoding and settles the actual stored size atomically with the tool success record. Objects use CREATE_NEW temporary files, fsync, handle-relative rename, private permissions, NOFOLLOW opens, regular-file/link-count checks, before/after inode checks, and bounded reads plus a content hash. O_RDWR prevents a FIFO replacement from blocking at open; the read channel performs no write. Tests exercise actual symlinks, hardlinks, inode replacement, ancestor rename, FIFO replacement and sentinel preservation on native ext4 as uid65534. The intended deployment root remains inaccessible to other OS users; these checks do not claim protection against a privileged host administrator controlling the process or procfs.

Every image-task checkpoint also reserves its complete serialized UTF-8 size as MEDIA_CHECKPOINT, atomically with insertion; reuse of an identical checkpoint does not charge twice. Its occupancy is `tenant_budget.media_checkpoint_held`. All storage admission checks compare `storage_held + media_checkpoint_held` with the existing storage limit. V14 backfills existing image checkpoints without changing their bytes or digests, including copies missed by old cleanup. Pre-existing excess occupancy remains visible and blocks new storage until released; migration never raises the configured quota. Historical V1–V13 migrations remain unchanged.

Terminal, quiescent SUCCEEDED/FAILED/CANCELLED tasks retain media for seven days after last update. Cleanup deletes only recorded upload identities. In one final transaction it removes every image checkpoint's `requestBody`, clears raw payloads, releases MEDIA_INPUT/MEDIA_STORAGE/MEDIA_CHECKPOINT, and marks objects/task DELETED. The checkpoint becomes MEDIA_PURGED and cannot be replayed: `request_digest`, `bodyDigest`, model/response/dispatch digests and scoped references retain their original meaning. `retained_request_digest` hashes the redacted audit snapshot; the original request digest is never recomputed to match redacted bytes. Remaining metadata is conservatively charged as part of the full checkpoint until purge. HELD_UNKNOWN in any reservation, NEEDS_RECONCILIATION, recoverable tasks and missing stop proof exclude cleanup. Failed object deletion or a rolled-back final transaction retains quota and retries; repeated cleanup cannot refund twice. Old binaries must not be restarted against the new schema. Disabling media does not roll back V13/V14 or erase history.

## Local verification

`tools/p4_image_verify.py --jar ... --evidence ...` runs a private pinned MySQL fixture and product control/worker JVMs with 256MiB heap as uid65534. It validates the received HTTP PNG container/CRC and decompresses IDAT, then exercises typed composition, presentation and authenticated download. `tools/image_store_verify.py` runs native filesystem and tracked-decoder scenarios. `ImageIT` collects both in foreground; `WorkspaceIT` covers the affected existing file/CAS path. No GitHub CI, real credentials, paid calls, shared services or deployment are used.

Use JDK21 and set `JIMU_IT_JAVA` to an existing native JDK21 executable for the Python runners. On Windows export it into WSL with `WSLENV=JIMU_IT_JAVA/u` before invoking Maven. Verification failures and uncovered cases must accompany the exact source/build identifiers in the handoff; a selective pass is not a claim that the entire historical matrix passed.
