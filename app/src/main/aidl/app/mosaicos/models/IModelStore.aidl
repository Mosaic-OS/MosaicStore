/*
 * Copyright (C) 2026 The MosaicOS Project
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
package app.mosaicos.models;

import android.os.ParcelFileDescriptor;
import app.mosaicos.models.IModelLease;
import app.mosaicos.models.IChatModelListener;

interface IModelStore {
    const int ABSENT = 0;
    const int DOWNLOADING = 1;
    const int VERIFYING = 2;
    const int INCOMPATIBLE = 3;
    const int CORRUPT = 4;
    const int READY = 5;
    const int IMPORTING = 6;
    const int REMOVING = 7;
    const int UNAVAILABLE = 8;

    int getState(String modelId);
    ParcelFileDescriptor openModel(String modelId, IModelLease lease);
    void releaseModel(String modelId, IModelLease lease);
    void reportCorrupt(String modelId, IModelLease lease);
    @nullable String getSelectedChatModelId();
    void setSelectedChatModelId(String modelId);
    void registerChatModelListener(IChatModelListener listener);
    void unregisterChatModelListener(IChatModelListener listener);
}
