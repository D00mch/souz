

```bash

echo $ClientId

```

# Get token

```bash


export Scope=SALUTE_SPEECH_PERS
export ClientId=79202bac-2d5d-48ea-aadd-e3e4e8d19b32
export VoiceKey=NzkyMDJiYWMtMmQ1ZC00OGVhLWFhZGQtZTNlNGU4ZDE5YjMyOjEwOGNlMjhkLTM0MzAtNDE1MC1iZTU1LTZkMDNlMTNlZmU5Mg==


export AccessToken=$(curl -L -X POST 'https://ngw.devices.sberbank.ru:9443/api/v2/oauth' \
-H 'Content-Type: application/x-www-form-urlencoded' \
-H 'Accept: application/json' \
-H 'RqUID: 58827082-89d7-4aa1-8560-188decd3548c' \
-H "Authorization: Basic $VoiceKey" \
--data-urlencode 'scope= SALUTE_SPEECH_PERS' | jq -r '.access_token')


echo $AccessToken | pbcopy
echo $AccessToken


osascript << EOF
on run {input, parameters}
    tell application "System Events"
        set frontAppName to name of first application process whose frontmost is true
        tell process frontAppName
            set buttonNames to {}
            set windowList to every window
            
            repeat with win in windowList
                try
                    set buttonList to every button of win
                    repeat with btn in buttonList
                        try
                            set btnName to name of btn
                            if btnName is not missing value and btnName is not "" then
                                copy btnName to end of buttonNames
                            end if
                        on error
                            -- Ignore buttons without names
                        end try
                    end repeat
                on error
                    -- Ignore windows that can't be accessed
                end try
            end repeat
            
            -- Remove duplicates (optional)
            set buttonNames to removeDuplicates(buttonNames)
            
            -- Convert to string safely
            if length of buttonNames > 0 then
                return buttonNames as string
            else
                return "No named buttons found"
            end if
        end tell
    end tell
end run

-- Helper: Remove duplicates from a list
on removeDuplicates(listOfItems)
    set uniqueItems to {}
    repeat with itemRef in listOfItems
        set itemVal to itemRef as string
        if uniqueItems does not contain itemVal then
            set end of uniqueItems to itemVal
        end if
    end repeat
    return uniqueItems
end removeDuplicates
EOF

```
