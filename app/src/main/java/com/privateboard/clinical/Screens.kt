package com.privateboard.clinical

import android.text.Html
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private fun stripHtml(s:String)=Html.fromHtml(s,Html.FROM_HTML_MODE_COMPACT).toString().trim()
private fun Number.fmt()="%,d".format(this)

@Composable private fun Header(title:String,subtitle:String?=null,back:(()->Unit)?=null,action:ImageVector?=null,onAction:()->Unit={}){
 Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
  if(back!=null) IconButton(back){Icon(Icons.Outlined.ArrowBack,"Back")}
  Column(Modifier.weight(1f)){Text(title,fontSize=25.sp,fontWeight=FontWeight.Black);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
  if(action!=null) IconButton(onAction){Icon(action,null)}
 }
}
@Composable private fun Metric(value:String,label:String,modifier:Modifier=Modifier){Column(modifier){Text(value,fontSize=25.sp,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary);Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable private fun Tag(text:String,color:Color=MaterialTheme.colorScheme.primaryContainer){Surface(shape=RoundedCornerShape(8.dp),color=color){Text(text.uppercase(),Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)}}

@Composable fun HomeScreen(vm:MainViewModel){
 val v=vm.stateVersion; val states=remember(v){vm.corpus.questions.associateWith(vm::state)}
 val attempted=states.count{it.value.attempts>0};val correct=states.values.sumOf{it.correct};val attempts=states.values.sumOf{it.attempts};val due=states.count{it.value.interval>0&&it.value.dueAt<=System.currentTimeMillis()};val favorites=states.count{it.value.favorited}
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)){
  item{Header("Clinical Deck","Internal medicine • entirely offline",action=Icons.Outlined.Settings,onAction={vm.screen=AppScreen.SETTINGS})}
  item{Card(Modifier.padding(20.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primary)){Column(Modifier.padding(24.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("TODAY'S ROUND",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Mint,letterSpacing=1.5.sp);Text(if(due>0)"$due reviews are due" else "Keep your recall sharp",fontSize=27.sp,fontWeight=FontWeight.Black,color=Color.White);Text("Short sessions. Durable memory.",color=Color.White.copy(.8f))};Icon(Icons.Filled.LocalHospital,null,tint=Mint,modifier=Modifier.size(44.dp))};Spacer(Modifier.height(20.dp));Button(onClick={vm.start(SessionConfig(count=20))},colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Teal)){Icon(Icons.Filled.PlayArrow,null);Spacer(Modifier.width(6.dp));Text("Start 20-question round")}}}}
  item{Row(Modifier.padding(horizontal=20.dp).fillMaxWidth()){Metric(attempted.fmt(),"seen",Modifier.weight(1f));Metric(if(attempts==0)"—" else "${(correct*100f/attempts).roundToInt()}%","accuracy",Modifier.weight(1f));Metric(favorites.fmt(),"saved",Modifier.weight(1f))}}
  item{Text("Quick practice",Modifier.padding(20.dp,26.dp,20.dp,10.dp),fontWeight=FontWeight.ExtraBold,fontSize=19.sp)}
  item{Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){QuickCard("Due review",Icons.Outlined.Schedule,due.coerceAtLeast(10).coerceAtMost(50)){vm.start(SessionConfig(count=due.coerceAtLeast(10).coerceAtMost(50)))};QuickCard("Exam sprint",Icons.Outlined.Timer,40){vm.start(SessionConfig(count=40,mode=SessionMode.EXAM))};QuickCard("Bookmarks",Icons.Outlined.Bookmark,favorites.coerceAtLeast(1)){vm.start(SessionConfig(count=50,favoritesOnly=true))}}}
  item{Text("Library pulse",Modifier.padding(20.dp,26.dp,20.dp,8.dp),fontWeight=FontWeight.ExtraBold,fontSize=19.sp)}
  items(vm.corpus.books.take(4)){BookRow(it,{vm.openBook(it)},states.filterKeys{q->q.bookId==it.id}.count{e->e.value.attempts>0})}
 }
}
@Composable private fun RowScope.QuickCard(label:String,icon:ImageVector,count:Int,onClick:()->Unit){Card(Modifier.weight(1f).clickable(onClick=onClick)){Column(Modifier.padding(14.dp)){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(12.dp));Text(label,fontWeight=FontWeight.Bold,fontSize=13.sp);Text(count.fmt(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}

@Composable fun LibraryScreen(vm:MainViewModel){
 val v=vm.stateVersion
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=20.dp)){item{Header("Library","7 sources • ${vm.corpus.count.fmt()} questions")};items(vm.corpus.books){b->val seen=remember(v,b.id){vm.corpus.questions.count{it.bookId==b.id&&vm.state(it).attempts>0}};BookRow(b,{vm.openBook(b)},seen)}}
}
@Composable private fun BookRow(book:Book,onClick:()->Unit,seen:Int){
 Card(Modifier.padding(horizontal=20.dp,vertical=6.dp).fillMaxWidth().clickable(onClick=onClick)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
  Box(Modifier.size(50.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){Text(book.title.take(1),fontSize=22.sp,fontWeight=FontWeight.Black,color=Teal)}
  Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(book.title,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${book.count.fmt()} questions${book.year?.let{" • $it"}?:""}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);LinearProgressIndicator({if(book.count==0)0f else seen.toFloat()/book.count},Modifier.padding(top=9.dp).fillMaxWidth())};Spacer(Modifier.width(12.dp));Icon(Icons.Outlined.ChevronRight,null)
 }}
}

@Composable fun BookScreen(vm:MainViewModel){val book=vm.selectedBook?:return;val qs=remember(book){vm.corpus.questions.filter{it.bookId==book.id}};var diff by remember{mutableStateOf<String?>(null)};var count by remember{mutableFloatStateOf(20f)};val sections=remember(qs){qs.groupingBy{it.section}.eachCount().entries.sortedByDescending{it.value}}
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp)){item{Header(book.title,"${book.count.fmt()} questions",back={vm.screen=AppScreen.LIBRARY})};item{Column(Modifier.padding(20.dp)){Text(book.authors.joinToString(),color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(20.dp));Text("Build a session",fontSize=20.sp,fontWeight=FontWeight.Black);Text("Difficulty",fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp,bottom=8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(null,"easy","medium","hard").forEach{d->FilterChip(selected=diff==d,onClick={diff=d},label={Text(d?.replaceFirstChar(Char::uppercase)?:"All")})}};Text("${count.toInt()} questions",fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp));Slider(count,{count=it},valueRange=5f..100f,steps=18);Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button({vm.start(SessionConfig(book.id,difficulty=diff,count=count.toInt()))},Modifier.weight(1f)){Icon(Icons.Filled.School,null);Text(" Study")};OutlinedButton({vm.start(SessionConfig(book.id,difficulty=diff,count=count.toInt(),mode=SessionMode.EXAM))},Modifier.weight(1f)){Icon(Icons.Outlined.Timer,null);Text(" Exam")}};Text("Chapters",fontSize=20.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=26.dp,bottom=8.dp))}}
  items(sections){s->ListItem(headlineContent={Text(s.key.ifBlank{"Uncategorized"},fontWeight=FontWeight.SemiBold)},supportingContent={Text("${s.value} questions")},trailingContent={Icon(Icons.Outlined.PlayCircle,null)},modifier=Modifier.clickable{vm.start(SessionConfig(book.id,section=s.key,count=s.value.coerceAtMost(50)))})}
 }
}

@Composable fun SearchScreen(vm:MainViewModel){val results=vm.searchResults();Column(Modifier.fillMaxSize()){Header("Find a question","Search questions, explanations, or chapters");OutlinedTextField(vm.search,{vm.search=it},Modifier.padding(horizontal=20.dp).fillMaxWidth(),leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon=if(vm.search.isNotEmpty()) { { IconButton({vm.search=""}){Icon(Icons.Filled.Close,null)} } } else null,placeholder={Text("e.g. hyperkalemia ECG")},singleLine=true);Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(vm.searchDifficulty==null,{vm.searchDifficulty=null},{Text("All")});listOf("easy","medium","hard").forEach{d->FilterChip(vm.searchDifficulty==d,{vm.searchDifficulty=if(vm.searchDifficulty==d)null else d},{Text(d.replaceFirstChar(Char::uppercase))})}};Text("${results.size}${if(results.size==250)"+" else ""} results",Modifier.padding(20.dp,8.dp),fontWeight=FontWeight.Bold);LazyColumn{items(results,key={it.id}){q->QuestionResult(q,vm)}}}}
@Composable private fun QuestionResult(q:Question,vm:MainViewModel){ListItem(headlineContent={Text(stripHtml(q.question),maxLines=3,overflow=TextOverflow.Ellipsis)},supportingContent={Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Tag(q.difficulty);Text(q.section,maxLines=1,overflow=TextOverflow.Ellipsis)}},trailingContent={IconButton({vm.favorite(q)}){Icon(if(vm.state(q).favorited)Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,null,tint=if(vm.state(q).favorited) Amber else LocalContentColor.current)}},modifier=Modifier.clickable{vm.sessionQuestions=listOf(q);vm.sessionMode=SessionMode.STUDY;vm.screen=AppScreen.SESSION})}

@Composable fun StatsScreen(vm:MainViewModel){val v=vm.stateVersion;val states=remember(v){vm.corpus.questions.associateWith(vm::state)};val attempts=states.values.sumOf{it.attempts};val correct=states.values.sumOf{it.correct};val seen=states.count{it.value.attempts>0};val mastery=if(vm.corpus.count==0)0f else seen.toFloat()/vm.corpus.count
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=20.dp)){item{Header("Progress","Stored only on this device")};item{Card(Modifier.padding(20.dp).fillMaxWidth()){Column(Modifier.padding(22.dp)){Text("Coverage",fontWeight=FontWeight.Black,fontSize=20.sp);Text("${(mastery*100).roundToInt()}%",fontSize=44.sp,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary);LinearProgressIndicator({mastery},Modifier.fillMaxWidth().height(8.dp));Text("$seen of ${vm.corpus.count.fmt()} questions seen",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}}};item{Row(Modifier.padding(horizontal=20.dp).fillMaxWidth()){Metric(attempts.fmt(),"answers",Modifier.weight(1f));Metric(correct.fmt(),"correct",Modifier.weight(1f));Metric(if(attempts==0)"—" else "${(correct*100f/attempts).roundToInt()}%","accuracy",Modifier.weight(1f))}};item{Text("By source",Modifier.padding(20.dp,28.dp,20.dp,8.dp),fontSize=20.sp,fontWeight=FontWeight.Black)};items(vm.corpus.books){b->val bookQs=states.filterKeys{it.bookId==b.id};val done=bookQs.count{it.value.attempts>0};val acc=bookQs.values.let{s->if(s.sumOf{it.attempts}==0)"—" else "${(s.sumOf{it.correct}*100f/s.sumOf{it.attempts}).roundToInt()}%"};ListItem(headlineContent={Text(b.title,maxLines=1,overflow=TextOverflow.Ellipsis)},supportingContent={LinearProgressIndicator({done.toFloat()/b.count},Modifier.fillMaxWidth())},trailingContent={Text(acc,fontWeight=FontWeight.Bold)})}}
}

@Composable fun SettingsScreen(vm:MainViewModel){var confirm by remember{mutableStateOf(false)};Column{Header("Settings","Private by design",back={vm.screen=AppScreen.HOME});ListItem(headlineContent={Text("Dark appearance")},supportingContent={Text("Use a low-glare palette")},leadingContent={Icon(Icons.Outlined.DarkMode,null)},trailingContent={Switch(vm.dark,{vm.toggleDark()})});ListItem(headlineContent={Text("Offline corpus")},supportingContent={Text("${vm.corpus.count.fmt()} questions • no account • no network permission")},leadingContent={Icon(Icons.Outlined.CloudOff,null)});ListItem(headlineContent={Text("Reset local progress")},supportingContent={Text("Remove attempts, bookmarks, and review schedule")},leadingContent={Icon(Icons.Outlined.DeleteSweep,null)},modifier=Modifier.clickable{confirm=true});if(confirm)AlertDialog({confirm=false},title={Text("Reset all progress?")},text={Text("This cannot be undone. The question library will remain available.")},confirmButton={TextButton({vm.reset();confirm=false}){Text("Reset")}},dismissButton={TextButton({confirm=false}){Text("Cancel")}})}}
