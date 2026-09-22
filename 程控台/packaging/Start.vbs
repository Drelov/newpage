Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = base
If fso.FileExists(base & "\pythonw.exe") Then
  shell.Run """" & base & "\pythonw.exe"" """ & base & "\main.py""", 0, False
Else
  MsgBox "pythonw.exe is missing. Extract the whole zip first.", 48, "Chengkongtai"
End If
