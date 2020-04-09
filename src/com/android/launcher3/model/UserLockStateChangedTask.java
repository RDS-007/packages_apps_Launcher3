/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.model;

import static com.android.launcher3.ItemInfoWithIcon.FLAG_DISABLED_LOCKED_USER;

import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.os.UserHandle;

import com.android.launcher3.ItemInfo;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.WorkspaceItemInfo;
import com.android.launcher3.shortcuts.ShortcutKey;
import com.android.launcher3.shortcuts.ShortcutRequest;
import com.android.launcher3.shortcuts.ShortcutRequest.QueryResult;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.ItemInfoMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Task to handle changing of lock state of the user
 */
public class UserLockStateChangedTask extends BaseModelUpdateTask {

    private final UserHandle mUser;
    private boolean mIsUserUnlocked;

    public UserLockStateChangedTask(UserHandle user, boolean isUserUnlocked) {
        mUser = user;
        mIsUserUnlocked = isUserUnlocked;
    }

    @Override
    public void execute(LauncherAppState app, BgDataModel dataModel, AllAppsList apps) {
        Context context = app.getContext();

        HashMap<ShortcutKey, ShortcutInfo> pinnedShortcuts = new HashMap<>();
        if (mIsUserUnlocked) {
            QueryResult shortcuts = new ShortcutRequest(context, mUser)
                    .query(ShortcutRequest.PINNED);
            if (shortcuts.wasSuccess()) {
                for (ShortcutInfo shortcut : shortcuts) {
                    pinnedShortcuts.put(ShortcutKey.fromInfo(shortcut), shortcut);
                }
            } else {
                // Shortcut manager can fail due to some race condition when the lock state
                // changes too frequently. For the purpose of the update,
                // consider it as still locked.
                mIsUserUnlocked = false;
            }
        }

        // Update the workspace to reflect the changes to updated shortcuts residing on it.
        ArrayList<WorkspaceItemInfo> updatedWorkspaceItemInfos = new ArrayList<>();
        HashSet<ShortcutKey> removedKeys = new HashSet<>();

        for (ItemInfo itemInfo : dataModel.itemsIdMap) {
            if (itemInfo.itemType == LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
                    && mUser.equals(itemInfo.user)) {
                WorkspaceItemInfo si = (WorkspaceItemInfo) itemInfo;
                if (mIsUserUnlocked) {
                    ShortcutKey key = ShortcutKey.fromItemInfo(si);
                    ShortcutInfo shortcut = pinnedShortcuts.get(key);
                    // We couldn't verify the shortcut during loader. If its no longer available
                    // (probably due to clear data), delete the workspace item as well
                    if (shortcut == null) {
                        removedKeys.add(key);
                        continue;
                    }
                    si.runtimeStatusFlags &= ~FLAG_DISABLED_LOCKED_USER;
                    si.updateFromDeepShortcutInfo(shortcut, context);
                    app.getIconCache().getShortcutIcon(si, shortcut);
                } else {
                    si.runtimeStatusFlags |= FLAG_DISABLED_LOCKED_USER;
                }
                updatedWorkspaceItemInfos.add(si);
            }
        }
        bindUpdatedWorkspaceItems(updatedWorkspaceItemInfos);
        if (!removedKeys.isEmpty()) {
            deleteAndBindComponentsRemoved(ItemInfoMatcher.ofShortcutKeys(removedKeys));
        }

        // Remove shortcut id map for that user
        Iterator<ComponentKey> keysIter = dataModel.deepShortcutMap.keySet().iterator();
        while (keysIter.hasNext()) {
            if (keysIter.next().user.equals(mUser)) {
                keysIter.remove();
            }
        }

        if (mIsUserUnlocked) {
            dataModel.updateDeepShortcutCounts(
                    null, mUser,
                    new ShortcutRequest(context, mUser).query(ShortcutRequest.ALL));
        }
        bindDeepShortcuts(dataModel);
    }
}
