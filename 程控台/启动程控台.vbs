Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = base
On Error Resume Next
shell.Run "pythonw.exe """ & base & "\main.py""", 0, False
If Err.Number <> 0 Then
  Err.Clear
  shell.Run "pyw.exe -3 """ & base & "\main.py""", 0, False
End If
If Err.Number <> 0 Then
  MsgBox "Python 3 was not found. Install Python and enable Add to PATH.", 48, "Chengkongtai"
End If
