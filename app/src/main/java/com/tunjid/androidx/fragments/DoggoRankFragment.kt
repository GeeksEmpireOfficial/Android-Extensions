package com.tunjid.androidx.fragments

import android.os.Bundle
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Pair
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.RecyclerView
import com.tunjid.androidx.R
import com.tunjid.androidx.core.components.args
import com.tunjid.androidx.core.content.themeColorAt
import com.tunjid.androidx.databinding.ViewholderDoggoRankBinding
import com.tunjid.androidx.isDarkTheme
import com.tunjid.androidx.model.Doggo
import com.tunjid.androidx.navigation.MultiStackNavigator
import com.tunjid.androidx.navigation.Navigator
import com.tunjid.androidx.navigation.activityNavigatorController
import com.tunjid.androidx.recyclerview.*
import com.tunjid.androidx.recyclerview.viewbinding.BindingViewHolder
import com.tunjid.androidx.recyclerview.viewbinding.viewHolderFrom
import com.tunjid.androidx.uidrivers.SlideInItemAnimator
import com.tunjid.androidx.uidrivers.activityGlobalUiController
import com.tunjid.androidx.uidrivers.update
import com.tunjid.androidx.view.util.InsetFlags
import com.tunjid.androidx.view.util.hashTransitionName
import com.tunjid.androidx.viewholders.DoggoBinder
import com.tunjid.androidx.viewholders.bind
import com.tunjid.androidx.viewmodels.DoggoRankViewModel
import com.tunjid.androidx.viewmodels.routeName
import kotlin.math.abs

class DoggoRankFragment : Fragment(R.layout.fragment_simple_list),
        Navigator.TransactionModifier {

    private var isRanking by args<Boolean>()
    private var uiState by activityGlobalUiController()
    private val viewModel by viewModels<DoggoRankViewModel>()
    private val navigator by activityNavigatorController<MultiStackNavigator>()

    private var recyclerView: RecyclerView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        uiState = uiState.copy(
                toolbarTitle = this::class.java.routeName,
                toolBarMenu = R.menu.menu_doggo,
                toolbarShows = true,
                toolbarOverlaps = false,
                toolbarMenuRefresher = {
                    it.findItem(R.id.menu_sort)?.isVisible = !isRanking
                    it.findItem(R.id.menu_browse)?.isVisible = isRanking
                },
                toolbarMenuClickListener = {
                    when (it.itemId) {
                        R.id.menu_browse -> isRanking = false
                        R.id.menu_sort -> isRanking = true
                    }
                    recyclerView?.notifyDataSetChanged()
                    ::uiState.update { copy(toolbarInvalidated = true) }
                },
                fabText = getString(R.string.reset_doggos),
                fabIcon = R.drawable.ic_restore_24dp,
                fabShows = true,
                showsBottomNav = true,
                insetFlags = InsetFlags.ALL,
                lightStatusBar = !requireContext().isDarkTheme,
                fabExtended = if (savedInstanceState == null) true else uiState.fabExtended,
                navBarColor = requireContext().themeColorAt(R.attr.nav_bar_color),
                fabClickListener = { viewModel.resetList() }
        )

        recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view).apply {
            itemAnimator = SlideInItemAnimator()
            layoutManager = gridLayoutManager(2) { if (isRanking) 2 else 1 }
            adapter = adapterOf(
                    itemsSource = viewModel::doggos,
                    viewHolderCreator = { parent, _ -> rankingViewHolder(parent) },
                    viewHolderBinder = { viewHolder, doggo, _ -> viewHolder.bind(isRanking, doggo) },
                    itemIdFunction = { it.hashCode().toLong() }
            )
            addScrollListener { _, dy -> if (abs(dy) > 4) uiState = uiState.copy(fabExtended = dy < 0) }
            setSwipeDragOptions<BindingViewHolder<ViewholderDoggoRankBinding>>(
                    itemViewSwipeSupplier = { isRanking },
                    longPressDragSupplier = { isRanking },
                    swipeConsumer = { holder, _ -> removeDoggo(holder) },
                    dragConsumer = ::moveDoggo,
                    dragHandleFunction = { it.binding.innerConstraintLayout.dragHandle },
                    swipeDragStartConsumer = { holder, actionState -> onSwipeOrDragStarted(holder, actionState) },
                    swipeDragEndConsumer = { viewHolder, actionState -> onSwipeOrDragEnded(viewHolder, actionState) }
            )

            viewModel.watchDoggos().observe(viewLifecycleOwner, this::acceptDiff)
        }

        postponeEnterTransition()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
    }

    override fun augmentTransaction(transaction: FragmentTransaction, incomingFragment: Fragment) {
        if (incomingFragment !is AdoptDoggoFragment) return

        val doggo = incomingFragment.doggo
        val holder: BindingViewHolder<ViewholderDoggoRankBinding> = recyclerView?.viewHolderForItemId(doggo.hashCode().toLong())
                ?: return

        val binding = holder.binding
        transaction
                .setReorderingAllowed(true)
                .addSharedElement(binding.innerConstraintLayout.doggoImage, binding.innerConstraintLayout.doggoImage.hashTransitionName(doggo))
    }

    private fun rankingViewHolder(parent: ViewGroup) =
            parent.viewHolderFrom(ViewholderDoggoRankBinding::inflate).apply {
                doggoBinder = object : DoggoBinder {
                    init {
                        itemView.setOnClickListener {
                            val doggo = doggo ?: return@setOnClickListener
                            Doggo.transitionDoggo = doggo
                            navigator.push(AdoptDoggoFragment.newInstance(doggo))
                        }
                    }

                    override var doggo: Doggo? = null
                    override val doggoName: TextView get() = binding.innerConstraintLayout.doggoName
                    override val thumbnail: ImageView get() = binding.innerConstraintLayout.doggoImage
                    override val fullResolution: ImageView? get() = null
                    override fun onDoggoThumbnailLoaded(doggo: Doggo) {
                        if (doggo == Doggo.transitionDoggo) startPostponedEnterTransition()
                    }
                }
            }

    private fun moveDoggo(start: BindingViewHolder<*>, end: BindingViewHolder<*>) {
        val from = start.adapterPosition
        val to = end.adapterPosition

        viewModel.swap(from, to)
        recyclerView?.notifyItemMoved(from, to)
        recyclerView?.notifyItemChanged(from)
        recyclerView?.notifyItemChanged(to)
    }

    private fun removeDoggo(viewHolder: BindingViewHolder<*>) {
        val position = viewHolder.adapterPosition
        val minMax = viewModel.remove(position)

        recyclerView?.notifyItemRemoved(position)
        // Only necessary to rebind views lower so they have the right position
        recyclerView?.notifyItemRangeChanged(minMax.first, minMax.second)
    }

    private fun onSwipeOrDragStarted(holder: BindingViewHolder<*>, actionState: Int) =
            viewModel.onActionStarted(Pair(holder.itemId, actionState))

    private fun onSwipeOrDragEnded(viewHolder: BindingViewHolder<*>, actionState: Int) {
        val message = viewModel.onActionEnded(Pair(viewHolder.itemId, actionState))
        if (!TextUtils.isEmpty(message)) uiState = uiState.copy(snackbarText = message)

    }

    companion object {
        fun newInstance(): DoggoRankFragment = DoggoRankFragment().apply { this.isRanking = true }
    }
}

var BindingViewHolder<ViewholderDoggoRankBinding>.doggoBinder by BindingViewHolder.Prop<DoggoBinder?>()

private fun BindingViewHolder<ViewholderDoggoRankBinding>.bind(isRanking: Boolean, doggo: Doggo) {
    val currentlyInRanking = (binding.innerConstraintLayout.doggoImage.layoutParams as ConstraintLayout.LayoutParams).matchConstraintPercentWidth == 0.18f

    if (isRanking != currentlyInRanking) ConstraintSet().run {
        TransitionManager.beginDelayedTransition(
                binding.innerConstraintLayout.innerConstraintLayout,
                AutoTransition().setDuration(200)
        )
        clone(binding.root.context, if (isRanking) R.layout.viewholder_doggo_rank_sort else R.layout.viewholder_doggo_rank_browse)
        applyTo(binding.innerConstraintLayout.innerConstraintLayout)
    }

    doggoBinder?.bind(doggo)
    binding.innerConstraintLayout.doggoRank.text = (adapterPosition + 1).toString()
}