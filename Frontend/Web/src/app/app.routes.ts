import { Routes } from '@angular/router';
import { HomeComponent } from './pages/home/home.component';
import { NextComponent } from './pages/next/next.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'next', component: NextComponent },
  { path: '**', redirectTo: '' }
];
