<#
.SYNOPSIS
	Adds the optional integrations to the fabric.mod.json of a ported version.

.DESCRIPTION
	The ports expose the same entrypoints as the main project: the Mod Menu integration and the
	Simple Voice Chat plugin. Both are optional, so they are only suggested, not required.
#>
param(
	[Parameter(Mandatory = $true)][string]$Target,
	[switch]$NoModMenu,
	[switch]$NoVoiceChat
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$file = Join-Path $root "versions\$Target\src\main\resources\fabric.mod.json"

$entryBlocks = @()
$suggestLines = @()

function Add-Entry([string]$name, [string]$class) {
	$script:entryBlocks += "		`"$name`": [`r`n			`"$class`"`r`n		]"
}

Add-Entry 'main' 'com.example.groupchat.GroupChat'
Add-Entry 'client' 'com.example.groupchat.client.GroupChatClient'
if (-not $NoModMenu) {
	Add-Entry 'modmenu' 'com.example.groupchat.client.ModMenuIntegration'
	$suggestLines += '		"modmenu": "*"'
}
if (-not $NoVoiceChat) {
	Add-Entry 'voicechat' 'com.example.groupchat.voice.SvcPlugin'
	$suggestLines += '		"voicechat": "*"'
}

$entryBlock = ($entryBlocks -join ",`r`n")
$suggestBlock = ''
if ($suggestLines.Count -gt 0) {
	$suggestBlock = "`r`n`t`"suggests`": {`r`n" + ($suggestLines -join ",`r`n") + "`r`n`t},"
}

$template = @'
{
	"schemaVersion": 1,
	"id": "groupchat",
	"version": "${version}",
	"name": "Group Chat",
	"description": "Discord-style group chats inside Minecraft: groups, invites, images, stickers and voice calls.",
	"authors": [
		"Mayank"
	],
	"license": "MIT",
	"icon": "assets/groupchat/icon.png",
	"environment": "*",
	"entrypoints": {
__ENTRIES__
	},
	"depends": {
		"fabricloader": ">=0.20.1",
		"minecraft": "~${mcVersion}",
		"java": ">=25",
		"fabric-api": "*"
	},__SUGGESTS__
	"contributors": [
		"Afkz Studio"
	]
}
'@

$text = $template.Replace('__ENTRIES__', $entryBlock).Replace('__SUGGESTS__', $suggestBlock)
Set-Content -Path $file -Value $text -NoNewline
Write-Host "updated the entrypoints of versions/$Target"
