package li.songe.gkd.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import li.songe.gkd.db.DbSet
import li.songe.gkd.ui.destinations.AppItemPageDestination
import li.songe.gkd.util.subsIdToRawFlow
import li.songe.gkd.util.subsItemsFlow
import javax.inject.Inject

@HiltViewModel
class AppItemVm @Inject constructor(stateHandle: SavedStateHandle) : ViewModel() {
    private val args = AppItemPageDestination.argsFrom(stateHandle)


    val subsItemFlow =
        subsItemsFlow.map { subsItems -> subsItems.find { s -> s.id == args.subsItemId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val subsConfigsFlow = DbSet.subsConfigDao.queryGroupTypeConfig(args.subsItemId, args.appId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val subsAppFlow =
        subsIdToRawFlow.map { subsIdToRaw -> subsIdToRaw[args.subsItemId]?.apps?.find { it.id == args.appId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

}