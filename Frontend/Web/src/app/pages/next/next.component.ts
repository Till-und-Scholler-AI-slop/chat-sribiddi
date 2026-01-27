import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-next',
  imports: [MatButtonModule, MatCardModule, RouterLink],
  templateUrl: './next.component.html',
  styleUrl: './next.component.scss'
})
export class NextComponent {}
