package com.privateboard.clinical

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity:ComponentActivity(){
 private val vm by viewModels<MainViewModel>()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{ClinicalTheme(vm.dark){ClinicalApp(vm)}}}
}

data class NavItem(val screen:AppScreen,val label:String,val icon:ImageVector)
private val navItems=listOf(NavItem(AppScreen.HOME,"Today",Icons.Outlined.Home),NavItem(AppScreen.LIBRARY,"Library",Icons.Outlined.MenuBook),NavItem(AppScreen.SEARCH,"Search",Icons.Outlined.Search),NavItem(AppScreen.STATS,"Progress",Icons.Outlined.Insights))

@Composable fun ClinicalApp(vm:MainViewModel){
 val root=vm.screen in navItems.map{it.screen}
 Scaffold(bottomBar={if(root) NavigationBar{navItems.forEach{item->NavigationBarItem(selected=vm.screen==item.screen,onClick={vm.screen=item.screen},icon={Icon(item.icon,null)},label={Text(item.label)})}}}){pad->
  Box(Modifier.padding(pad).fillMaxSize()){
   when(vm.screen){
    AppScreen.HOME->HomeScreen(vm);AppScreen.LIBRARY->LibraryScreen(vm);AppScreen.SEARCH->SearchScreen(vm);AppScreen.STATS->StatsScreen(vm)
    AppScreen.BOOK->BookScreen(vm);AppScreen.SESSION->SessionScreen(vm);AppScreen.SETTINGS->SettingsScreen(vm)
   }
  }
 }
}
