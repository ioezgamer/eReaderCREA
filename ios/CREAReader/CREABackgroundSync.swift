import BackgroundTasks
import GoogleSignIn
import Foundation

/// iOS chooses when to run this refresh; manual synchronization stays available.
enum CREABackgroundSync {
    static let identifier = "org.crea.reader.ios.refresh"
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            let operation = Task { @MainActor in
                guard UserDefaults.standard.bool(forKey: "crea.reader.autoSync") else { refresh.setTaskCompleted(success: true); return }
                schedule()
                do {
                    let user: GIDGoogleUser = try await withCheckedThrowingContinuation { continuation in
                        GIDSignIn.sharedInstance.restorePreviousSignIn { user, error in
                            if let user { continuation.resume(returning: user) }
                            else { continuation.resume(throwing: error ?? URLError(.userAuthenticationRequired)) }
                        }
                    }
                    try Task.checkCancellation()
                    guard user.grantedScopes?.contains("https://www.googleapis.com/auth/drive.appdata") == true else {
                        refresh.setTaskCompleted(success: false); return
                    }
                    let library = LocalLibrary.shared, catalog = CREADriveCatalog(cleanupTemporaryFiles: false), progress = CREAProgressSync()
                    await catalog.synchronize()
                    try Task.checkCancellation()
                    await progress.pull(catalog: catalog, library: library)
                    try Task.checkCancellation()
                    await progress.push(catalog: catalog, library: library)
                    refresh.setTaskCompleted(success: !Task.isCancelled && catalog.errorMessage == nil)
                } catch { refresh.setTaskCompleted(success: false) }
            }
            refresh.expirationHandler = { operation.cancel() }
        }
    }
    static func schedule() {
        guard UserDefaults.standard.bool(forKey: "crea.reader.autoSync") else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier); return
        }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { NSLog("CREA Reader background refresh: %@", error.localizedDescription) }
    }
}
