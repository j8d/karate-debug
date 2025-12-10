import * as assert from 'assert';
import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';

suite('Karate Runner Extension Test Suite', () => {
    const fixturesPath = path.join(__dirname, '../../test-fixtures');

    test('Extension should be present', () => {
        assert.ok(vscode.extensions.getExtension('resmed.karate-runner'));
    });

    test('Extension should activate', async () => {
        const ext = vscode.extensions.getExtension('resmed.karate-runner');
        assert.ok(ext);
        await ext.activate();
        assert.strictEqual(ext.isActive, true);
    });

    test('Should register karate debug type', async () => {
        const ext = vscode.extensions.getExtension('resmed.karate-runner');
        await ext?.activate();
        
        const debugTypes = vscode.extensions.all
            .flatMap(e => e.packageJSON?.contributes?.debuggers || [])
            .map(d => d.type);
        
        assert.ok(debugTypes.includes('karate'));
    });

    test('Should register commands', async () => {
        const ext = vscode.extensions.getExtension('resmed.karate-runner');
        await ext?.activate();

        const commands = await vscode.commands.getCommands(true);
        
        assert.ok(commands.includes('karateRunner.debugFeature'));
        assert.ok(commands.includes('karateRunner.selectEnvironment'));
        assert.ok(commands.includes('karateRunner.refreshFeatures'));
    });

    test('Should provide CodeLens for feature files', async () => {
        const featureFile = path.join(fixturesPath, 'src/test/java/users/get-user.feature');
        
        if (fs.existsSync(featureFile)) {
            const doc = await vscode.workspace.openTextDocument(featureFile);
            await vscode.window.showTextDocument(doc);
            
            // Wait for CodeLens to be computed
            await new Promise(resolve => setTimeout(resolve, 1000));
            
            const codeLenses = await vscode.commands.executeCommand<vscode.CodeLens[]>(
                'vscode.executeCodeLensProvider',
                doc.uri
            );
            
            assert.ok(codeLenses && codeLenses.length > 0, 'Should have CodeLens items');
        }
    });

    test('Feature file should have Karate language ID', async () => {
        const featureFile = path.join(fixturesPath, 'src/test/java/users/get-user.feature');
        
        if (fs.existsSync(featureFile)) {
            const doc = await vscode.workspace.openTextDocument(featureFile);
            assert.strictEqual(doc.languageId, 'karate');
        }
    });
});

