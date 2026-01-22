import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';

/**
 * DocumentLinkProvider for Karate feature files.
 * Creates clickable links for:
 * - classpath:path/to/file
 * - read('path/to/file') or read("path/to/file")
 * - call read('path') patterns
 * - @tagName references (jumps to tag in current file)
 */
export class KarateDocumentLinkProvider implements vscode.DocumentLinkProvider {

    // Patterns to detect file references
    private static readonly PATTERNS = [
        // classpath: references
        /classpath:([^\s'">\)]+)/g,
        // read('...') or read("...")
        /read\s*\(\s*['"]([^'"]+)['"]\s*\)/g,
    ];

    // Pattern for @tag references in read('@tagName')
    private static readonly TAG_PATTERN = /read\s*\(\s*['"]@([^'"]+)['"]\s*\)/g;

    provideDocumentLinks(
        document: vscode.TextDocument,
        _token: vscode.CancellationToken
    ): vscode.ProviderResult<vscode.DocumentLink[]> {
        const links: vscode.DocumentLink[] = [];
        const text = document.getText();

        // Track ranges we've already linked to avoid duplicates (check for overlaps)
        const linkedRanges: Array<{start: number, end: number}> = [];

        const hasOverlap = (start: number, end: number): boolean => {
            return linkedRanges.some(r => !(end <= r.start || start >= r.end));
        };

        // Process file reference patterns
        for (const pattern of KarateDocumentLinkProvider.PATTERNS) {
            let match;
            const regex = new RegExp(pattern.source, pattern.flags);

            while ((match = regex.exec(text)) !== null) {
                const filePath = match[1];
                const startOffset = match.index + match[0].indexOf(filePath);
                const endOffset = startOffset + filePath.length;

                // Skip if this range overlaps with an existing link
                if (hasOverlap(startOffset, endOffset)) {
                    continue;
                }

                const startPos = document.positionAt(startOffset);
                const endPos = document.positionAt(endOffset);
                const range = new vscode.Range(startPos, endPos);

                // Check if path contains @tagName suffix
                const atIndex = filePath.indexOf('@');
                if (atIndex > 0) {
                    // Has tag reference: file.feature@tagName
                    const filePathOnly = filePath.substring(0, atIndex);
                    const tagName = filePath.substring(atIndex + 1);

                    const resolvedUri = this.resolveFilePath(document, filePathOnly);
                    if (resolvedUri) {
                        linkedRanges.push({start: startOffset, end: endOffset});

                        // Check if it's the same file
                        const isSameFile = resolvedUri.fsPath === document.uri.fsPath;

                        if (isSameFile) {
                            // Same file - just jump to tag
                            const tagLine = this.findTagInDocument(document, tagName);
                            if (tagLine !== -1) {
                                const uri = document.uri.with({ fragment: `L${tagLine + 1}` });
                                const link = new vscode.DocumentLink(range, uri);
                                link.tooltip = `Jump to @${tagName}`;
                                links.push(link);
                            }
                        } else {
                            // Different file - open file and jump to tag
                            const targetUri = this.resolveFileWithTag(resolvedUri, tagName);
                            const link = new vscode.DocumentLink(range, targetUri);
                            link.tooltip = `Open ${path.basename(filePathOnly)} and jump to @${tagName}`;
                            links.push(link);
                        }
                    }
                } else {
                    // No tag - just file reference
                    const resolvedUri = this.resolveFilePath(document, filePath);
                    if (resolvedUri) {
                        linkedRanges.push({start: startOffset, end: endOffset});
                        const link = new vscode.DocumentLink(range, resolvedUri);
                        link.tooltip = `Open ${path.basename(filePath)}`;
                        links.push(link);
                    }
                }
            }
        }

        // Process standalone @tag references (read('@tagName') without file path)
        let tagMatch;
        const tagRegex = new RegExp(KarateDocumentLinkProvider.TAG_PATTERN.source, KarateDocumentLinkProvider.TAG_PATTERN.flags);

        while ((tagMatch = tagRegex.exec(text)) !== null) {
            const tagName = tagMatch[1];
            // Skip if this is a file@tag reference (already handled above)
            if (tagName.includes('/') || tagName.includes('.')) {
                continue;
            }

            const fullMatch = tagMatch[0];
            const tagInMatch = `@${tagName}`;
            const startOffset = tagMatch.index + fullMatch.indexOf(tagInMatch);
            const endOffset = startOffset + tagInMatch.length;

            // Skip if this range overlaps with an existing link
            if (hasOverlap(startOffset, endOffset)) {
                continue;
            }

            const startPos = document.positionAt(startOffset);
            const endPos = document.positionAt(endOffset);
            const range = new vscode.Range(startPos, endPos);

            // Find the tag in the current document
            const tagLine = this.findTagInDocument(document, tagName);
            if (tagLine !== -1) {
                linkedRanges.push({start: startOffset, end: endOffset});
                const uri = document.uri.with({ fragment: `L${tagLine + 1}` });
                const link = new vscode.DocumentLink(range, uri);
                link.tooltip = `Jump to @${tagName}`;
                links.push(link);
            }
        }

        return links;
    }

    private resolveFileWithTag(fileUri: vscode.Uri, tagName: string): vscode.Uri {
        // Read the target file and find the tag line
        try {
            const content = fs.readFileSync(fileUri.fsPath, 'utf-8');
            const lines = content.split('\n');
            const tagPattern = new RegExp(`^\\s*@${this.escapeRegex(tagName)}\\b`, 'm');

            for (let i = 0; i < lines.length; i++) {
                if (tagPattern.test(lines[i])) {
                    return fileUri.with({ fragment: `L${i + 1}` });
                }
            }
        } catch (e) {
            // If we can't read the file, just return the file URI without fragment
        }
        return fileUri;
    }

    private resolveFilePath(document: vscode.TextDocument, filePath: string): vscode.Uri | undefined {
        const workspaceFolder = vscode.workspace.getWorkspaceFolder(document.uri);
        if (!workspaceFolder) {
            return undefined;
        }

        const workspaceRoot = workspaceFolder.uri.fsPath;

        // Strip classpath: prefix if present
        let resolvedPath = filePath;
        if (resolvedPath.startsWith('classpath:')) {
            resolvedPath = resolvedPath.substring('classpath:'.length);
        }

        // Strip @tagName suffix if present (e.g., file.feature@tagName -> file.feature)
        const atIndex = resolvedPath.indexOf('@');
        if (atIndex > 0) {
            resolvedPath = resolvedPath.substring(0, atIndex);
        }

        // Common source directories to search
        const searchPaths = [
            resolvedPath,
            `src/test/java/${resolvedPath}`,
            `src/test/resources/${resolvedPath}`,
            `src/main/java/${resolvedPath}`,
            `src/main/resources/${resolvedPath}`,
        ];

        // Also search relative to the current document
        const documentDir = path.dirname(document.uri.fsPath);
        searchPaths.push(path.join(documentDir, resolvedPath));

        for (const searchPath of searchPaths) {
            const fullPath = path.isAbsolute(searchPath) 
                ? searchPath 
                : path.join(workspaceRoot, searchPath);
            
            if (fs.existsSync(fullPath)) {
                return vscode.Uri.file(fullPath);
            }
        }

        return undefined;
    }

    private findTagInDocument(document: vscode.TextDocument, tagName: string): number {
        const tagPattern = new RegExp(`^\\s*@${this.escapeRegex(tagName)}\\b`, 'm');
        
        for (let i = 0; i < document.lineCount; i++) {
            const line = document.lineAt(i).text;
            if (tagPattern.test(line)) {
                return i;
            }
        }
        
        return -1;
    }

    private escapeRegex(str: string): string {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }
}

