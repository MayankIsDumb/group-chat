<#
.SYNOPSIS
	Copies the current features from the main project into one of the ported Minecraft versions.

.DESCRIPTION
	The root project targets Minecraft 1.21.11, the ports under versions/ target the 26.x line.
	Both share the same features, but the 26.x game API differs in a handful of places:

	  * GuiGraphics became GuiGraphicsExtractor and drawString/drawCenteredString became
	    text/centeredText
	  * screens are opened with setScreenAndShow and draw through extractRenderState
	  * 26.2 moved the chat, the overlay and the screen accessor
	  * the key bindings use the 26.x key mapping helper and InputConstants key codes
	  * there is no org.lwjgl.glfw on the compile classpath

	This script copies the files over and rewrites those calls, so the ports stay in sync with
	the main project instead of drifting apart.
#>
param(
	[Parameter(Mandatory = $true)][string]$Target,
	[switch]$NewAccessors,
	[switch]$NoModMenu,
	[switch]$NoVoiceChat
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'src'
$destination = Join-Path $root "versions\$Target\src"

if (-not (Test-Path $destination)) {
	throw "no such port: $Target"
}

$files = @(
	'client\java\com\example\groupchat\client\ClientConfig.java'
	'client\java\com\example\groupchat\client\GifKeyScreen.java'
	'client\java\com\example\groupchat\client\GroupChatClient.java'
	'client\java\com\example\groupchat\client\GroupChatConfigScreen.java'
	'client\java\com\example\groupchat\client\media\GifCache.java'
	'client\java\com\example\groupchat\client\media\GifDecoder.java'
	'client\java\com\example\groupchat\client\media\GifTexture.java'
	'client\java\com\example\groupchat\client\media\ImageUtil.java'
	'client\java\com\example\groupchat\client\media\TenorClient.java'
	'client\java\com\example\groupchat\client\ui\GifPickerScreen.java'
	'client\java\com\example\groupchat\client\ui\HubScreen.java'
	'client\java\com\example\groupchat\client\ui\UiKit.java'
	'client\java\com\example\groupchat\client\voice\VoiceBackend.java'
	'client\java\com\example\groupchat\client\voice\VoiceClient.java'
	'client\java\com\example\groupchat\client\waypoint\WaypointManager.java'
	'main\java\com\example\groupchat\Constants.java'
	'main\java\com\example\groupchat\net\Payloads.java'
	'main\java\com\example\groupchat\net\Protocol.java'
	'main\java\com\example\groupchat\server\GroupManager.java'
	'main\java\com\example\groupchat\server\ImageStore.java'
	'main\java\com\example\groupchat\server\ServerNet.java'
	'main\resources\assets\groupchat\lang\en_us.json'
)

if (-not $NoModMenu) {
	$files += 'client\java\com\example\groupchat\client\ModMenuIntegration.java'
	$files += 'client\java\com\example\groupchat\client\dev\ModMenuCheck.java'
}

if (-not $NoVoiceChat) {
	$files += 'main\java\com\example\groupchat\voice\VoiceBridge.java'
	$files += 'main\java\com\example\groupchat\voice\SvcVoiceBridge.java'
	$files += 'main\java\com\example\groupchat\voice\SvcPlugin.java'
}

# Every rewrite of the game API, as a pattern and its replacement.
$rules = [ordered]@{
	'net\.minecraft\.client\.gui\.GuiGraphics;' = 'net.minecraft.client.gui.GuiGraphicsExtractor;'
	'\bGuiGraphics\b' = 'GuiGraphicsExtractor'
	'\.drawCenteredString\(' = '.centeredText('
	'\.drawString\(' = '.text('
	'public void render\(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick\)' = 'public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)'
	'public void renderBackground\(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick\)' = 'public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)'
	'super\.render\(graphics, mouseX, mouseY, partialTick\)' = 'super.extractRenderState(graphics, mouseX, mouseY, partialTick)'
	'TextureCache\.Entry' = 'TextureCache.CachedTexture'
	'\bEntry entry = ' = 'CachedTexture entry = '
	'\.setScreen\(' = '.setScreenAndShow('
	'\.gui\.getChat\(\)\.addMessage\(' = '.gui.getChat().addClientSystemMessage('
	'import org\.lwjgl\.glfw\.GLFW;\r?\n' = ''
	'GLFW\.GLFW_KEY_KP_ENTER' = 'InputConstants.KEY_NUMPADENTER'
	'GLFW\.GLFW_KEY_ENTER' = 'InputConstants.KEY_RETURN'
	'GLFW\.GLFW_KEY_ESCAPE' = 'InputConstants.KEY_ESCAPE'
	'GLFW\.GLFW_KEY_([A-Z])' = 'InputConstants.KEY_$1'
	'InputConstants\.Type\.KEYSYM,\s*' = ''
	'import net\.fabricmc\.fabric\.api\.client\.keybinding\.v1\.KeyBindingHelper;' = 'import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;'
	'KeyBindingHelper\.registerKeyBinding' = 'KeyMappingHelper.registerKeyMapping'
	'PayloadTypeRegistry\.playC2S\(\)' = 'PayloadTypeRegistry.serverboundPlay()'
	'PayloadTypeRegistry\.playS2C\(\)' = 'PayloadTypeRegistry.clientboundPlay()'
}

# The rules that only apply to the versions that moved the chat, the overlay and the screen.
$newRules = [ordered]@{
	'\.gui\.getChat\(\)\.addClientSystemMessage\(' = '.gui.hud.getChat().addClientSystemMessage('
	'client\.getOverlay\(\)' = 'client.gui.overlay()'
	'\bclient\.screen\b' = 'client.gui.screen()'
	'\bminecraft\.screen\b' = 'minecraft.gui.screen()'
	'client\.getMainRenderTarget\(\)' = 'client.gameRenderer.mainRenderTarget()'
}

foreach ($file in $files) {
	$from = Join-Path $source $file
	$to = Join-Path $destination $file
	New-Item -ItemType Directory -Force -Path (Split-Path $to -Parent) | Out-Null
	$text = Get-Content $from -Raw

	foreach ($rule in $rules.GetEnumerator()) {
		$text = $text -replace $rule.Key, $rule.Value
	}
	if ($NewAccessors) {
		foreach ($rule in $newRules.GetEnumerator()) {
			$text = $text -replace $rule.Key, $rule.Value
		}
	}

	# the game keys come from InputConstants now, so the import has to be there
	if ($text -match 'InputConstants\.' -and $text -notmatch 'import com\.mojang\.blaze3d\.platform\.InputConstants;') {
		$text = $text -replace '(import net\.minecraft\.)', "import com.mojang.blaze3d.platform.InputConstants;`r`n`$1"
	}

	Set-Content -Path $to -Value $text -NoNewline
}

# the dev harness of the port gets the mod menu check
if (-not $NoModMenu) {
	$harness = Join-Path $destination 'client\java\com\example\groupchat\client\dev\DebugHarness.java'
	$text = Get-Content $harness -Raw
	if ($text -notmatch 'ModMenuCheck') {
		$pattern = '(\t\t\t\} else \{\r?\n\t\t\t\tAgentScenario\.init\(AGENT\);)'
		$replacement = "`t`t`t} else if (AGENT.equals(`"modmenu`")) {`r`n`t`t`t`tModMenuCheck.init();`r`n`t`t`t} else {`r`n`t`t`t`tAgentScenario.init(AGENT);"
		$text = $text -replace $pattern, $replacement
		Set-Content -Path $harness -Value $text -NoNewline
	}
}

Write-Host "ported $($files.Count) files into versions/$Target"
