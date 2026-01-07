#!/usr/bin/env node
/**
 * Sets build environment configuration for the extension.
 * 
 * Usage:
 *   node scripts/set-build-env.js dev   # Set dev values
 *   node scripts/set-build-env.js prod  # Set prod values (default)
 */

const fs = require('fs');
const path = require('path');

const CONFIG_FILE = path.join(__dirname, '..', 'src', 'config.ts');

const ENVIRONMENTS = {
    dev: {
        API_BASE_URL: 'http://localhost:3000/api',
        GITHUB_CLIENT_ID: 'Ov23liVNnHehsgEqo38c'
    },
    prod: {
        API_BASE_URL: 'https://karate-debug-api.vercel.app/api',
        GITHUB_CLIENT_ID: 'Ov23lilyMXLAitkqwqPL'
    }
};

const env = process.argv[2] || 'prod';

if (!ENVIRONMENTS[env]) {
    console.error(`Unknown environment: ${env}`);
    console.error('Valid environments: dev, prod');
    process.exit(1);
}

const config = ENVIRONMENTS[env];

const content = `/**
 * Extension configuration values.
 * 
 * These values are replaced at build time by scripts/set-build-env.js
 * Default values are PRODUCTION - do not change these defaults.
 * 
 * Dev values:
 *   API_BASE_URL: 'http://localhost:3000/api'
 *   GITHUB_CLIENT_ID: 'Ov23liVNnHehsgEqo38c'
 * 
 * Prod values:
 *   API_BASE_URL: 'https://karate-debug-api.vercel.app/api'
 *   GITHUB_CLIENT_ID: 'Ov23lilyMXLAitkqwqPL'
 */

export const API_BASE_URL = '${config.API_BASE_URL}';
export const GITHUB_CLIENT_ID = '${config.GITHUB_CLIENT_ID}';
`;

fs.writeFileSync(CONFIG_FILE, content);

// Check if this is a reset after dev build (called with 'prod' after packaging)
const isReset = env === 'prod' && process.argv[3] !== '--verbose';
const resetFlag = process.argv.includes('--reset');

if (resetFlag || (env === 'prod' && !process.argv.includes('--verbose'))) {
    // Silent reset after dev build - only show if explicitly requested
    if (process.argv.includes('--verbose')) {
        console.log(`✓ Reset source to: ${env}`);
    }
} else {
    console.log(`✓ Building with: ${env}`);
    console.log(`  API_BASE_URL: ${config.API_BASE_URL}`);
    console.log(`  GITHUB_CLIENT_ID: ${config.GITHUB_CLIENT_ID}`);
}

