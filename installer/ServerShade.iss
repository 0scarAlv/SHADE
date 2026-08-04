#define MyAppName "Server Shade"
#define MyAppVersion "0.1.0"
#define MyAppExeName "Shade.Agent.exe"

[Setup]
AppId={{8DE8219A-136B-4F71-92EB-BD12596BA8C0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=Oscar Alvarado
DefaultDirName={autopf}\ServerShade
DefaultGroupName=Server Shade
DisableProgramGroupPage=yes
OutputBaseFilename=ServerShadeSetup
OutputDir=..\dist
Compression=lzma
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Files]
Source: "..\dist\agent\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Desinstalar {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Iniciar {#MyAppName} ahora"; Flags: postinstall nowait skipifsilent
