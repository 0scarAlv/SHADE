#define MyAppName "Server Shade"
#define MyAppVersion "1.4.0"
#define MyAppExeName "Shade.Agent.exe"

[Setup]
AppId={{8DE8219A-136B-4F71-92EB-BD12596BA8C0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=Oscar Alvarado
PrivilegesRequired=lowest
DefaultDirName={localappdata}\Programs\ServerShade
DefaultGroupName=Server Shade
DisableProgramGroupPage=yes
OutputBaseFilename=ServerShadeSetup
OutputDir=..\dist
Compression=lzma
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\src\Shade.Agent\shade.ico
WizardImageFile=wizard-large.bmp,wizard-large@2x.bmp
WizardSmallImageFile=wizard-small.bmp,wizard-small@2x.bmp
WizardStyle=modern
; Offers to close a running agent (it locks its own files) instead of failing mid-install.
CloseApplications=yes
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"; Flags: unchecked

[Files]
Source: "..\dist\agent\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Desinstalar {#MyAppName}"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Iniciar {#MyAppName} ahora"; Flags: postinstall nowait skipifsilent
