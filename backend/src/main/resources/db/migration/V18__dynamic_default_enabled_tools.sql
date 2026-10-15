-- Settings without enabledTools follow every advertised backend tool. Earlier first reads stored the
-- tools advertised at that moment, which hid every later tool. A stored list is such a snapshot when
-- it holds every current tool added up to some release and none added later; it is removed. Lists
-- with a gap, without current tools, or empty stay explicit selections. A user who deselected exactly
-- the newest tools of their time is indistinguishable from a snapshot and follows the default.
-- OAuth tools exist only where OAuth is configured, so they decide nothing.
-- Releases: 1 server agent (2026-05-03), 2 images (05-17), 3 channel messaging (08-18),
-- 4 ReadFile (09-14), 5 scheduled tasks (10-14).
with introduced(name, added) as (values
    ('Calculator', 1), ('CreatePlot', 1), ('DeleteFile', 1), ('EditFile', 1), ('ExcelRead', 1),
    ('ExcelReport', 1), ('ExtractTextFromFile', 1), ('FindFilesByName', 1), ('FindFolders', 1),
    ('ListFiles', 1), ('MoveFile', 1), ('NewFile', 1), ('ReadPdfPages', 1), ('SearchFileContent', 1),
    ('GenerateImage', 2), ('ViewImage', 2),
    ('ListActiveChannels', 3), ('SendMessageToChannel', 3),
    ('ReadFile', 4),
    ('scheduled-task.create', 5), ('scheduled-task.delete', 5), ('scheduled-task.list', 5)
)
update user_settings
set settings_json = settings_json - 'enabledTools'
where (select max(added) from introduced where (settings_json -> 'enabledTools') @> jsonb_build_array(name))
    < coalesce((select min(added) from introduced where not (settings_json -> 'enabledTools') @> jsonb_build_array(name)), 6);
