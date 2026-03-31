# Testing Pricing Notification

This document describes how to test the pricing notification feature that informs existing users about the price reduction from $29.99 to $9.99.

## 🚀 Quick Start

**VS Code:**
```bash
cd vscode && npm install && code .
# Press F5, then run "Karate Debug: Test Reset Pricing Notification", reload window
```

**IntelliJ:**
```bash
cd intellij
./gradlew runIde
# Tools → Reset Pricing Notification (Test), then restart IDE
```

## Prerequisites

Both VS Code and IntelliJ require:
- User must be **logged in** (have a `userId` in storage)
- User must have a record in the `users` table in Supabase

## VS Code Testing

### Setup

1. **Build and Launch Extension Development Host**:
   ```bash
   cd vscode
   npm install
   npm run compile
   # Press F5 in VS Code to launch Extension Development Host
   ```

2. **Ensure You're Logged In**:
   - Open Command Palette (Cmd+Shift+P)
   - Run: `Karate Debug: Sign In with GitHub`
   - Complete the GitHub OAuth flow

### Test the Notification

#### Method 1: Using Test Command (Recommended)

1. **Clear the notification flag**:
   - Command Palette → `Karate Debug: Test Reset Pricing Notification`
   - You should see: "Pricing notification flag cleared. Reload window to test notification."

2. **Reload the window**:
   - Command Palette → `Developer: Reload Window`

3. **Verify notification appears**:
   - After reload, you should see the notification:
     **"🎉 Great news! Karate Debug is now $9.99 (previously $29.99). Purchase today to unlock unlimited debugging!"**
   - With buttons: "Learn More" | "Purchase Now"

4. **Test the buttons**:
   - Click **"Learn More"** → Should open `https://www.karatedebug.com/?pricing=announcement&ide=vscode`
   - Clear flag again and reload, click **"Purchase Now"** → Should start checkout flow

#### Method 2: Manual Global State Clearing

1. **Open Extension Development Host**
2. **Open Developer Tools**: Help → Toggle Developer Tools
3. **Execute in Console**:
   ```javascript
   // This won't work directly in console - need to use command instead
   ```
   Use Method 1 instead.

### Expected Behavior

- ✅ Notification appears **only once** after updating to v0.7.6
- ✅ Only shows for **logged-in users** (not anonymous trials)
- ✅ Does **not** appear on subsequent window reloads (unless flag is manually cleared)
- ✅ "Learn More" opens browser to correct URL
- ✅ "Purchase Now" initiates checkout

---

## IntelliJ Testing

### Setup

1. **Build and Launch Plugin**:
   ```bash
   # IMPORTANT: Must cd into intellij directory first
   cd intellij
   ./gradlew runIde

   # Alternative: Use -p flag to specify project directory
   # ./intellij/gradlew -p intellij runIde
   ```

   **Alternative - Using IntelliJ IDEA**:
   - Open the `intellij` folder in IntelliJ IDEA
   - Run → Debug 'Run Plugin' (or 'Run IDE for UI Tests')

2. **Ensure You're Logged In**:
   - Look for status bar widget (bottom right)
   - If not logged in: Click widget → Sign in with GitHub
   - Complete the GitHub OAuth flow

### Test the Notification

1. **Clear the notification flag**:
   - Go to **Tools → Reset Pricing Notification (Test)**
   - You should see balloon notification: "Pricing notification flag cleared. Restart IDE to test notification."

2. **Restart the IDE**:
   - File → Exit (or Cmd+Q on Mac)
   - Launch again via `./gradlew runIde` or Debug configuration

3. **Verify notification appears**:
   - After restart, you should see a balloon notification in the bottom-right:
     **"Karate Debug Price Reduction"**
     **"🎉 Great news! Karate Debug is now $9.99 (previously $29.99). Purchase today to unlock unlimited debugging!"**
   - With action links: "Learn More" | "Purchase Now"

4. **Test the actions**:
   - Click **"Learn More"** → Should open `https://www.karatedebug.com/?pricing=announcement&ide=intellij`
   - Clear flag again and restart, click **"Purchase Now"** → Should start checkout flow

### Expected Behavior

- ✅ Balloon notification appears **only once** after updating to v0.2.3
- ✅ Only shows for **logged-in users** (not anonymous trials)
- ✅ Does **not** appear on subsequent IDE restarts (unless flag is manually cleared)
- ✅ "Learn More" opens browser to correct URL
- ✅ "Purchase Now" initiates checkout

---

## Testing Edge Cases

### 1. Anonymous Users (No Login)
- **Setup**: Logout via status bar widget
- **Expected**: No pricing notification should appear
- **Verify**: Only logged-in users see the notification

### 2. First-Time Users
- **Setup**: Fresh install, never logged in before
- **Expected**: No pricing notification (they don't have a record in `users` table yet)
- **Note**: They'll get notification after they sign up and update later

### 3. Notification Already Shown
- **Setup**: Don't clear the flag, just reload/restart
- **Expected**: No notification (flag prevents duplicate)
- **Verify**: Notification only appears once per version

---

## Cleanup

### VS Code
To permanently clear the notification flag:
```
Command Palette → Developer: Reset Extension Global State
```
Then reload window.

### IntelliJ
The test action clears the flag. To manually clear:
```bash
# Find and edit the properties file
# Location varies by OS:
# macOS: ~/Library/Application Support/JetBrains/IntelliJIdea<version>/options/
# Look for ide.general.xml or similar
```

---

## Troubleshooting

### Notification doesn't appear
1. **Check you're logged in**: Status bar should show username or trial status
2. **Check flag is cleared**: Use the test commands to reset
3. **Check console logs**: Look for errors in Developer Tools (VS Code) or idea.log (IntelliJ)

### Buttons don't work
1. **Check network**: Ensure you can reach karatedebug.com
2. **Check console**: Look for errors when clicking buttons
3. **Verify URLs**: Make sure they include correct query parameters (`?pricing=announcement&ide=...`)

