# Instructions for running a BLAP Program

### Function to add to $PROFILE. Called by: blap (BLAP file here) in your Powershell terminal
function blap {
    [CmdletBinding()]
    param (
        # A mandatory string parameter
        [Parameter(Mandatory = $true)]
        [string]$fileToRead
    )
    java (Enter Path Here)/BlapInterpreter.java $fileToRead
}

### Alternative - directly do this in your Powershell terminal
    java (Enter Path Here)/BlapInterpreter.java (BLAP file here)
