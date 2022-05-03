package com.ysar.stopfundwar.util

import androidx.annotation.DrawableRes
import com.ysar.stopfundwar.R

sealed class OnBoardingPage(
    @DrawableRes
    val image: Int,
    val title: String,
    val description: String
) {
    object First : OnBoardingPage(
        image = R.drawable.first,
        title = "Everyday Ukrainians \n" +
                "are dying",
        description = "Lorem ipsum dolor sit amet, consectetur \n" +
                "adipisicing elit, sed do eiusmod tempor \n" +
                "incididunt ut labore et dolore."
    )

    object Second : OnBoardingPage(
        image = R.drawable.second,
        title = "Every day Russia \n" +
                "sends new troops",
        description = "Lorem ipsum dolor sit amet, consectetur \n" +
                "adipisicing elit, sed do eiusmod."
    )

    object Third : OnBoardingPage(
        image = R.drawable.third,
        title = "Help fall Russia's \n" +
                "economy faster",
        description = "Say NO to Putin, say NO to all companies, that supports war in Ukraine"
    )
}
