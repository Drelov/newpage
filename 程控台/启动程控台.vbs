Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = base
On Error Resume Next
If fso.FileExists(base & "\pythonw.exe") Then
  shell.Run """" & base & "\pythonw.exe"" """ & base & "\main.py""", 0, False
  WScript.Quit 0
End If
If fso.FileExists(base & "\程控台.exe") Then
  shell.Run """" & base & "\程控台.exe""", 1, False
  WScript.Quit 0
End If
shell.Run "pythonw.exe """ & base & "\main.py""", 0, False
If Err.Number <> 0 Then
  Err.Clear
  shell.Run "pyw.exe -3 """ & base & "\main.py""", 0, False
End If
If Err.Number <> 0 Then
  MsgBox "pythonw.exe was not found. Use the zip package, or install Python and enable Add to PATH.", 48, "Chengkongtai"
End If
